package local.photoscan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import local.photoscan.PictureQuadFit.Vertex;

/** Original crops take precedence. A reviewed merge needs surviving child evidence. */
final class LocalCropArbitration {
    static final String POLICY = "contained80_distinct027_majority50_singleton20_v2";

    record Item(String id, Vertex[] quad, boolean original, boolean supported, boolean reviewed,
                boolean singletonSalvage) {
        Item(String id, Vertex[] quad, boolean original, boolean supported, boolean reviewed) {
            this(id, quad, original, supported, reviewed, false);
        }
    }
    record Decision(String disposition, List<Integer> children) {}

    static List<Decision> resolve(List<Item> items, int parent) {
        int count = items.size();
        String[] states = new String[count];
        boolean[] potentialMerge = new boolean[count];
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Item item = items.get(i);
            states[i] = !PictureContentDetector.eligibleFallbackProposal(item.quad())
                    ? item.original() ? "unresolved" : "rejected_local"
                    : !item.original() && !item.supported() ? "rejected_local" : "crop";
            children.add(List.of());
        }
        // The strict area hierarchy makes all child links acyclic.
        for (int i = 0; i < count; i++) {
            Item item = items.get(i);
            potentialMerge[i] = active(states, i) && item.reviewed()
                    && (!item.original() || i == parent) && proof(items, states, i).size() >= 2;
        }
        List<Integer> singletons = singletonEvidence(items, states, parent);
        for (int i = 0; i < count; i++) {
            if (items.get(i).original() || !active(states, i)) continue;
            for (int j = 0; j < count; j++) {
                if (!items.get(j).original() || !active(states, j) || potentialMerge[j]) continue;
                if (j == parent && singletons.size() == 1 && i == singletons.get(0)) continue;
                if (nested(items.get(i).quad(), items.get(j).quad())) {
                    states[i] = "deduplicated_local";
                    break;
                }
            }
        }
        // Prefer a complete larger local crop over a nested local fragment.
        // Potential merges wait until their smaller surviving outputs are known.
        for (int i = 0; i < count; i++) {
            if (items.get(i).original() || potentialMerge[i] || !active(states, i)) continue;
            for (int j = 0; j < count; j++) {
                if (i == j || items.get(j).original() || potentialMerge[j] || !active(states, j)) continue;
                double a = LocalPictureLocator.area(items.get(i).quad());
                double b = LocalPictureLocator.area(items.get(j).quad());
                if ((b > a || b == a && j < i) && nested(items.get(i).quad(), items.get(j).quad())) {
                    states[i] = "deduplicated_local";
                    break;
                }
            }
        }
        List<Integer> localMerges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!items.get(i).original() && potentialMerge[i] && active(states, i)) {
                localMerges.add(i);
                states[i] = "pending_merge";
            }
        }
        localMerges.sort(Comparator.comparingDouble((Integer i) -> LocalPictureLocator.area(items.get(i).quad()))
                .thenComparingInt(i -> i));
        for (int i : localMerges) {
            List<Integer> proof = proof(items, states, i);
            if (proof.size() >= 2) {
                states[i] = "suppressed_parent";
                children.set(i, proof);
            } else {
                states[i] = covered(items, states, i) ? "deduplicated_local" : "crop";
            }
        }
        if (parent >= 0 && parent < count && items.get(parent).original()
                && items.get(parent).reviewed() && active(states, parent)) {
            List<Integer> proof = proof(items, states, parent);
            if (proof.size() >= 2) {
                states[parent] = "suppressed_parent";
                children.set(parent, proof);
            } else if (singletons.size() == 1 && active(states, singletons.get(0))
                    && singletonEvidence(items, states, parent).equals(singletons)) {
                states[parent] = "unsupported_parent_unresolved";
                children.set(parent, singletons);
            } else {
                // A correct middle-photo parent is preserved. Failed split evidence
                // never turns it into an invented complete set of children.
                for (int i = 0; i < count; i++) {
                    if (!items.get(i).original() && active(states, i)
                            && nested(items.get(i).quad(), items.get(parent).quad())) {
                        states[i] = "deduplicated_local";
                    }
                }
            }
        }
        // Parent precedence may discard a local merge's witnesses. Do not report
        // that merge as suppressed unless its final surviving proof still holds.
        for (int i : localMerges) {
            if (!"suppressed_parent".equals(states[i])) continue;
            List<Integer> proof = proof(items, states, i);
            if (proof.size() >= 2) children.set(i, proof);
            else {
                states[i] = covered(items, states, i) ? "deduplicated_local" : "crop";
                children.set(i, List.of());
            }
        }
        List<Decision> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new Decision(states[i], children.get(i)));
        return result;
    }

    private static List<Integer> proof(List<Item> items, String[] states, int parent) {
        List<Integer> selected = new ArrayList<>();
        Vertex[] large = items.get(parent).quad();
        if (large == null) return selected;
        for (int i = 0; i < items.size(); i++) {
            Item child = items.get(i);
            if (i == parent || !active(states, i) || !child.supported() || !contained(large, child.quad())) continue;
            boolean distinct = true;
            for (int j : selected) {
                if (PicturePageDecode.iou(child.quad(), items.get(j).quad()) > .27) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) selected.add(i);
        }
        if (selected.size() >= 2) {
            // Conservative lower bound on the union inside the parent.
            double covered = 0;
            for (int j = 0; j < selected.size(); j++) {
                Vertex[] child = items.get(selected.get(j)).quad();
                covered += intersection(large, child);
                for (int k = 0; k < j; k++) covered -= intersection(child, items.get(selected.get(k)).quad());
            }
            if (covered < .50 * LocalPictureLocator.area(large)) return List.of();
        }
        return selected;
    }

    private static List<Integer> singletonEvidence(List<Item> items, String[] states, int parent) {
        if (parent < 0 || parent >= items.size() || !active(states, parent)) return List.of();
        Item suspect = items.get(parent);
        if (!suspect.original() || !suspect.reviewed() || suspect.supported() || !suspect.singletonSalvage()) {
            return List.of();
        }
        double parentArea = LocalPictureLocator.area(suspect.quad());
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item child = items.get(i);
            if (child.original() || !active(states, i) || !child.supported()
                    || !contained(suspect.quad(), child.quad())
                    || intersection(suspect.quad(), child.quad()) < .20 * parentArea) continue;
            boolean owned = false;
            for (int j = 0; j < items.size() && !owned; j++) {
                if (j != parent && items.get(j).original() && active(states, j)
                        && nested(child.quad(), items.get(j).quad())) owned = true;
            }
            if (!owned) result.add(i);
        }
        return result;
    }

    private static boolean covered(List<Item> items, String[] states, int candidate) {
        for (int i = 0; i < items.size(); i++) {
            if (i != candidate && active(states, i) && nested(items.get(candidate).quad(), items.get(i).quad())) {
                return true;
            }
        }
        return false;
    }

    private static boolean contained(Vertex[] parent, Vertex[] child) {
        if (parent == null || child == null) return false;
        double a = LocalPictureLocator.area(parent), b = LocalPictureLocator.area(child);
        return Double.isFinite(a) && Double.isFinite(b) && a > 0 && b > 0
                && a >= 2 * b && intersection(parent, child) >= .80 * b;
    }

    private static boolean nested(Vertex[] a, Vertex[] b) {
        if (a == null || b == null) return false;
        double aa=LocalPictureLocator.area(a),bb=LocalPictureLocator.area(b);
        return Double.isFinite(aa) && Double.isFinite(bb) && aa > 0 && bb > 0
                && intersection(a, b) >= .80 * Math.min(aa,bb);
    }

    private static double intersection(Vertex[] a, Vertex[] b) {
        double iou = PicturePageDecode.iou(a, b);
        return iou * (LocalPictureLocator.area(a) + LocalPictureLocator.area(b)) / (1 + iou);
    }

    private static boolean active(String[] states, int i) {
        return "crop".equals(states[i]);
    }

    private LocalCropArbitration() {}
}
