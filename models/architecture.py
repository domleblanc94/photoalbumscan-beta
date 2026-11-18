"""Selected locator/refiner architecture definitions; no data or trained tensors.
Instantiating defaults creates untrained networks; the Android app uses the pinned ONNX exports.
"""
import torch
from torch import nn
from torch.nn import functional as F
from torchvision.models import mobilenet_v3_small
import timm


def conv(in_channels, out_channels, kernel=1, groups=1):
    return nn.Sequential(nn.Conv2d(in_channels, out_channels, kernel, padding=kernel // 2,
                                   groups=groups, bias=False),
                         nn.BatchNorm2d(out_channels), nn.ReLU(inplace=True))


class PrintDetector(nn.Module):
    def __init__(self):
        super().__init__()
        backbone = mobilenet_v3_small(weights=None).features
        self.fine = backbone[:4]
        self.mid = backbone[4:9]
        self.coarse = backbone[9:12]
        self.fine_projection = conv(24, 40)
        self.mid_projection = conv(48, 40)
        self.coarse_projection = conv(96, 40)
        self.refine = nn.Sequential(conv(40, 40, 3, 40), conv(40, 48))
        self.center_head = nn.Sequential(conv(48, 32, 3), nn.Conv2d(32, 1, 1))
        self.corner_head = nn.Sequential(conv(48, 48, 3), nn.Conv2d(48, 8, 1))
        nn.init.constant_(self.center_head[-1].bias, -2.2)
        nn.init.normal_(self.corner_head[-1].weight, std=.01)
        nn.init.zeros_(self.corner_head[-1].bias)

    def forward(self, image):
        fine = self.fine(image)
        mid = self.mid(fine)
        coarse = self.coarse(mid)
        refined = self.refine(self.fine_projection(fine)
                              + F.interpolate(self.mid_projection(mid), scale_factor=2, mode="nearest")
                              + F.interpolate(self.coarse_projection(coarse), scale_factor=4, mode="nearest"))
        return self.center_head(refined), self.corner_head(refined)


class PictureROIModel(nn.Module):
    def __init__(self,pretrained=False,checkpoint=None):
        super().__init__()
        kwargs={'pretrained_cfg_overlay':{'file':str(checkpoint)}} if checkpoint else {}
        self.encoder=timm.create_model('mobilenetv3_small_100.lamb_in1k',pretrained=pretrained,
                                      features_only=True,out_indices=(1,2,3,4),**kwargs)
        self.register_buffer('rgb_mean',torch.tensor([.485,.456,.406]).view(1,3,1,1))
        self.register_buffer('rgb_std',torch.tensor([.229,.224,.225]).view(1,3,1,1))
        self.projections=nn.ModuleList([nn.Conv2d(c+1,48,1) for c in self.encoder.feature_info.channels()])
        self.fuse=nn.Sequential(nn.Conv2d(48,48,3,padding=1),nn.ReLU(),nn.Conv2d(48,48,3,padding=1),nn.ReLU())
        self.head=nn.Conv2d(48,2,1)

    def forward(self,x):
        features=self.encoder((x[:,:3]-self.rgb_mean)/self.rgb_std)
        shape=features[0].shape[-2:];fused=0
        for feature,projection in zip(features,self.projections):
            prior=F.interpolate(x[:,3:4],size=feature.shape[-2:],mode='bilinear',align_corners=False)
            fused=fused+F.interpolate(projection(torch.cat([feature,prior],1)),size=shape,mode='bilinear',align_corners=False)
        logits=self.head(self.fuse(fused))
        # Native stride4 logits. No final interpolation that would alter fitter lattice.
        return logits[:,:1],logits[:,1:2]


