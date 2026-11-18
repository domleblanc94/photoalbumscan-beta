package local.photoscan;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

/** Port of the frozen Python V3 fractional mask fitter. */
final class PictureQuadFit {
    private static final float THRESHOLD = .5f;

    record Vertex(float x, float y) {
        Point openCv() { return new Point(x, y); }
    }

    record Result(Vertex[] quad, List<String> reasons, double dominance, double expansion,
                  double omitted, double[] sideContinuity, double[] sideSupport) {
        boolean reviewRequired() { return !reasons.isEmpty(); }
    }

    record RecoveryResult(Result result, Result extrapolatedSupport) {}
    record ConstrainedRecoveryResult(Result result, Result extrapolatedSupport,
                                     Result constrainedSupport, double[] lineOffsetDelta, int borderContacts) {}

    private record Line(double nx, double ny, double offset, double coverage) {}

    private static final class Recovery {
        final double[] transform;
        final int sourceWidth, sourceHeight;
        final boolean constrainLines;
        Result extrapolatedSupport;
        Result constrainedSupport;
        double[] lineOffsetDelta;
        int borderContacts;

        Recovery(double[] transform, int sourceWidth, int sourceHeight, boolean constrainLines) {
            this.transform = transform;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.constrainLines = constrainLines;
        }
    }

    private PictureQuadFit() {}

    /** Advisory support of a final outline against the retained original ROI mask.
     * This does not fit a quad or change detector geometry. Outside-ROI evidence
     * remains unknown; reported fractions use the original largest component.
     */
    static CropReviewPolicy.FitEvidence reviewAtSourceQuad(float[] probability,float[] boundary,
            int width,int height,double[] transform,double[][] sourceQuad) {
        if(probability==null||boundary==null||probability.length!=width*height||boundary.length!=probability.length
                ||transform==null||transform.length!=6||!L1GeometryFallback.valid(sourceQuad,0,0))return null;
        byte[] binary=new byte[probability.length];boolean[] visited=new boolean[binary.length],component=null;int largest=0;
        for(int i=0;i<binary.length;i++){
            if(!Float.isFinite(probability[i])||!Float.isFinite(boundary[i]))return null;
            binary[i]=(byte)(probability[i]>=THRESHOLD?1:0);
        }
        for(int seed=0;seed<binary.length;seed++)if(binary[seed]!=0&&!visited[seed]){
            ArrayDeque<Integer> queue=new ArrayDeque<>();List<Integer> members=new ArrayList<>();queue.add(seed);visited[seed]=true;
            while(!queue.isEmpty()){
                int at=queue.removeFirst();members.add(at);int x=at%width,y=at/width;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=width||ny>=height)continue;
                    int next=ny*width+nx;if(binary[next]!=0&&!visited[next]){visited[next]=true;queue.add(next);}
                }
            }
            if(members.size()>largest){largest=members.size();component=new boolean[binary.length];for(int i:members)component[i]=true;}
        }
        if(component==null||largest==0)return null;
        double[][] grid=new double[4][2];
        for(int i=0;i<4;i++){
            grid[i][0]=(transform[0]*sourceQuad[i][0]+transform[1]*sourceQuad[i][1]+transform[2]-1.5)/4;
            grid[i][1]=(transform[3]*sourceQuad[i][0]+transform[4]*sourceQuad[i][1]+transform[5]-1.5)/4;
        }
        int covered=0,outside=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            boolean inside=true;for(int i=0;i<4;i++)if(L1GeometryFallback.cross(grid[i],grid[(i+1)%4],new double[]{x,y})<0){inside=false;break;}
            if(inside){if(component[y*width+x])covered++;else outside++;}
        }
        List<Vertex> points=new ArrayList<>(crossings(probability,binary,component,width,height,false));
        points.addAll(crossings(probability,binary,component,width,height,true));boolean weak=false;
        for(int i=0;i<4;i++){
            double[] a=grid[i],b=grid[(i+1)%4];double dx=b[0]-a[0],dy=b[1]-a[1],length=Math.hypot(dx,dy);
            if(!Double.isFinite(length)||length<=0)return null;int samples=Math.max(4,(int)Math.rint(length)),near=0;double support=0;
            for(int k=0;k<samples;k++){
                double t=.18+.64*k/(samples-1),x=a[0]+t*dx,y=a[1]+t*dy;
                for(Vertex p:points)if(Math.pow(x-p.x(),2)+Math.pow(y-p.y(),2)<=2.25){near++;break;}
                double best=Double.NEGATIVE_INFINITY;
                for(int step=-2;step<=2;step++){
                    int px=(int)Math.rint(x-step*dy/length),py=(int)Math.rint(y+step*dx/length);
                    // No invented edge evidence beyond the retained model input.
                    if(px>=0&&py>=0&&px<width&&py<height)best=Math.max(best,boundary[py*width+px]);
                }
                support+=Double.isFinite(best)?best:0;
            }
            weak|=(double)near/samples<.65||support/samples<.25;
        }
        double horizontal=Math.max(Math.hypot(sourceQuad[1][0]-sourceQuad[0][0],sourceQuad[1][1]-sourceQuad[0][1]),Math.hypot(sourceQuad[3][0]-sourceQuad[2][0],sourceQuad[3][1]-sourceQuad[2][1]));
        double vertical=Math.max(Math.hypot(sourceQuad[2][0]-sourceQuad[1][0],sourceQuad[2][1]-sourceQuad[1][1]),Math.hypot(sourceQuad[0][0]-sourceQuad[3][0],sourceQuad[0][1]-sourceQuad[3][1]));
        return new CropReviewPolicy.FitEvidence((double)(largest-covered)/largest,(double)outside/largest,weak,Math.max(horizontal,vertical)/Math.min(horizontal,vertical));
    }

    static Result fit(float[] probability, float[] boundary, int width, int height) {
        return fit(probability, boundary, width, height, null, false);
    }

    /** Float hull traversal matches the frozen host fitter for edge retries. */
    static Result fitSourceEdgeRetry(float[] probability, float[] boundary, int width, int height) {
        return fit(probability, boundary, width, height, null, true);
    }

    /** Optional fitter policy. The observed contour may replace only an out-of-bounds extrapolation. */
    static RecoveryResult fitWithBoundedRecovery(float[] probability, float[] boundary, int width, int height,
                                                  double[] sourceToInput, int sourceWidth, int sourceHeight) {
        Recovery recovery = new Recovery(sourceToInput, sourceWidth, sourceHeight, false);
        Result result = fit(probability, boundary, width, height, recovery, false);
        return new RecoveryResult(result, recovery.extrapolatedSupport);
    }

    static ConstrainedRecoveryResult fitWithConstrainedRecovery(float[] probability, float[] boundary,
            int width, int height, double[] sourceToInput, int sourceWidth, int sourceHeight) {
        return fitWithConstrainedRecovery(probability, boundary, width, height, sourceToInput,
                sourceWidth, sourceHeight, false);
    }

    static ConstrainedRecoveryResult fitWithConstrainedRecovery(float[] probability, float[] boundary,
            int width, int height, double[] sourceToInput, int sourceWidth, int sourceHeight,
            boolean nativeHull) {
        if (!ConstrainedLineOffsets.validTransform(sourceToInput)) {
            return new ConstrainedRecoveryResult(reject("invalid_roi_geometry", Double.NaN), null, null, null, 0);
        }
        Recovery recovery = new Recovery(sourceToInput, sourceWidth, sourceHeight, true);
        Result result = fit(probability, boundary, width, height, recovery, nativeHull);
        return new ConstrainedRecoveryResult(result, recovery.extrapolatedSupport,
                recovery.constrainedSupport, recovery.lineOffsetDelta, recovery.borderContacts);
    }

    private static Result fit(float[] probability, float[] boundary, int width, int height,
                              Recovery recovery, boolean nativeHull) {
        if (width < 1 || height < 1 || probability.length != (long) width * height) {
            return reject("invalid_mask", Double.NaN);
        }
        if (boundary != null && boundary.length != probability.length) {
            return reject("invalid_boundary", Double.NaN);
        }
        byte[] binary = new byte[probability.length];
        int active = 0;
        for (int i = 0; i < binary.length; i++) {
            float value = probability[i];
            if (!Float.isFinite(value) || value < 0 || value > 1) return reject("invalid_mask", Double.NaN);
            if (boundary != null && !Float.isFinite(boundary[i])) return reject("invalid_boundary", Double.NaN);
            if (value >= THRESHOLD) {
                binary[i] = 1;
                active++;
            }
        }
        if (active == 0) return reject("empty_mask", Double.NaN);
        Mat input = new Mat(height, width, CvType.CV_8U);
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        boolean[] component = new boolean[binary.length];
        int area = 0;
        try {
            input.put(0, 0, binary);
            int count = Imgproc.connectedComponentsWithStats(input, labels, stats, centroids, 8, CvType.CV_32S);
            int selected = 0;
            for (int i = 1; i < count; i++) {
                int candidate = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
                if (candidate > area) {
                    area = candidate;
                    selected = i;
                }
            }
            int[] membership = new int[binary.length];
            labels.get(0, 0, membership);
            RoiObservation.component(width,height,count,selected,area,active,membership,recovery!=null,nativeHull);
            for (int i = 0; i < binary.length; i++) component[i] = membership[i] == selected;
        } finally {
            centroids.release();
            stats.release();
            labels.release();
            input.release();
        }
        double dominance = (double) area / active;
        int contacted = 0;
        for (int y = 0; y < height; y++) {
            if (component[y * width]) contacted |= PictureModelInput.LEFT;
            if (component[y * width + width - 1]) contacted |= PictureModelInput.RIGHT;
        }
        for (int x = 0; x < width; x++) {
            if (component[x]) contacted |= PictureModelInput.TOP;
            if (component[(height - 1) * width + x]) contacted |= PictureModelInput.BOTTOM;
        }
        if (contacted != 0) {
            if (recovery != null) recovery.borderContacts = contacted;
            return reject("grid_border_contact", dominance);
        }

        List<Vertex> horizontal = crossings(probability, binary, component, width, height, false);
        List<Vertex> vertical = crossings(probability, binary, component, width, height, true);
        List<Vertex> points = new ArrayList<>(horizontal);
        points.addAll(vertical);
        Vertex[] seed = seed(nativeHull ? FloatContourHull.points(points) : hull(points));
        if (seed == null) return reject("non_quad_mask", dominance);
        Line[] lines = new Line[4];
        for (int i = 0; i < 4; i++) {
            Vertex start = seed[i], end = seed[(i + 1) % 4];
            lines[i] = fitSide(Math.abs(end.x - start.x) >= Math.abs(end.y - start.y) ? vertical : horizontal,
                    start, end);
            if (lines[i] == null) return reject("weak_side", dominance);
        }
        Vertex[] quad = new Vertex[4];
        for (int i = 0; i < 4; i++) {
            Line first = lines[(i + 3) % 4], second = lines[i];
            double det = first.nx * second.ny - first.ny * second.nx;
            if (Math.abs(det) < .30) return reject("parallel_sides", dominance);
            quad[i] = new Vertex((float) ((first.offset * second.ny - first.ny * second.offset) / det),
                    (float) ((first.nx * second.offset - first.offset * second.nx) / det));
        }
        if (!positiveConvex(quad)) return reject("invalid_intersections", dominance);
        if (recovery != null && recovery.constrainLines && !finite(quad)) {
            return reject("invalid_intersections", dominance);
        }
        boolean outsideGrid = !insideBounds(quad, width, height, 0);
        if (outsideGrid && recovery == null) return reject("out_of_grid", dominance);
        Result extrapolated = support(probability, boundary, width, height, component, points, quad, lines, area, dominance);
        if (recovery == null) return extrapolated;
        if (!outsideGrid) {
            Vertex[] mapped = toSource(quad, recovery.transform);
            if (validSourceQuad(mapped, recovery.sourceWidth, recovery.sourceHeight)) return extrapolated;
            if (!finiteConvexSource(mapped)) return reject("invalid_source_quad", dominance);
        }
        recovery.extrapolatedSupport = extrapolated;
        Result rejected = reject(outsideGrid ? "out_of_grid" : "invalid_source_quad", dominance);
        if (recovery.constrainLines) {
            if (boundary == null || !contentSupported(extrapolated)) return rejected;
            double[][] normals = new double[4][2];
            double[] offsets = new double[4];
            for (int i = 0; i < 4; i++) {
                normals[i][0] = lines[i].nx;
                normals[i][1] = lines[i].ny;
                offsets[i] = lines[i].offset;
            }
            var projected = ConstrainedLineOffsets.project(normals, offsets, recovery.transform, width, height,
                    recovery.sourceWidth, recovery.sourceHeight);
            if (projected == null || !positiveConvex(projected.quad())
                    || !insideBounds(projected.quad(), width, height, 0)) return rejected;
            Vertex[] mapped = toSource(projected.quad(), recovery.transform);
            if (!validSourceQuad(mapped, recovery.sourceWidth, recovery.sourceHeight)
                    || !insideBounds(mapped, recovery.sourceWidth, recovery.sourceHeight, 0)) return rejected;
            Result bounded = support(probability, boundary, width, height, component, points,
                    projected.quad(), lines, area, dominance);
            if (!contentSupported(bounded)) return rejected;
            recovery.constrainedSupport = bounded;
            recovery.lineOffsetDelta = projected.delta();
            return new Result(bounded.quad(), Collections.singletonList("constrained_line_recovery"), dominance,
                    bounded.expansion(), bounded.omitted(), bounded.sideContinuity(), bounded.sideSupport());
        }
        if (boundary == null || !positiveConvex(seed) || !insideBounds(seed, width, height, 0)) return rejected;
        Vertex[] mapped = toSource(seed, recovery.transform);
        if (!validSourceQuad(mapped, recovery.sourceWidth, recovery.sourceHeight)
                || !insideBounds(mapped, recovery.sourceWidth, recovery.sourceHeight, 0)) {
            return rejected;
        }
        // The seed vertices are samples on the observed contour. Reject chords that
        // cross off-component sampled centers, even if ordinary support would pass.
        Result bounded = support(probability, boundary, width, height, component, points, seed, lines, area, dominance);
        if (bounded.reviewRequired() || bounded.expansion() != 0) return rejected;
        return new Result(bounded.quad(), Collections.singletonList("bounded_contour_recovery"), dominance,
                bounded.expansion(), bounded.omitted(), bounded.sideContinuity(), bounded.sideSupport());
    }

    static boolean contentSupported(Result fit) {
        // Weak edge support is review evidence, not a mask-content failure.
        for (String reason : fit.reasons()) if (!reason.equals("weak_side")) return false;
        return true;
    }

    private static boolean finiteConvexSource(Vertex[] quad) {
        return finite(quad) && positiveConvex(quad) && signedArea(quad) >= 4;
    }

    private static boolean finite(Vertex[] quad) {
        for (Vertex point : quad) {
            if (!Float.isFinite(point.x) || !Float.isFinite(point.y)) return false;
        }
        return true;
    }

    private static boolean insideBounds(Vertex[] quad, int width, int height, double tolerance) {
        for (Vertex p : quad) {
            if (!(p.x >= -tolerance && p.x <= width - 1 + tolerance
                    && p.y >= -tolerance && p.y <= height - 1 + tolerance)) return false;
        }
        return true;
    }

    private static List<Vertex> crossings(float[] values, byte[] foreground, boolean[] component,
                                           int width, int height, boolean transpose) {
        List<Vertex> points = new ArrayList<>();
        int rows = transpose ? width : height;
        int cols = transpose ? height : width;
        int stride = transpose ? width : 1;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols - 1; col++) {
                int i = transpose ? col * width + row : row * width + col;
                int j = i + stride;
                boolean forward = component[i] && foreground[j] == 0;
                boolean backward = component[j] && foreground[i] == 0;
                if (forward || backward) {
                    float pos = forward ? values[i] + values[j] - .5f : 1.5f - values[i] - values[j];
                    points.add(transpose ? new Vertex(row, col + pos) : new Vertex(col + pos, row));
                }
            }
        }
        return points;
    }

    /** Monotonic chain keeps the fractional samples that MatOfPoint would round. */
    private static List<Vertex> hull(List<Vertex> input) {
        List<Vertex> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingDouble(Vertex::x).thenComparingDouble(Vertex::y));
        if (sorted.size() < 2) return sorted;
        List<Vertex> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            addHull(lower, sorted.get(i));
            addHull(upper, sorted.get(sorted.size() - 1 - i));
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static void addHull(List<Vertex> chain, Vertex p) {
        while (chain.size() >= 2 && turn(chain.get(chain.size() - 2), chain.get(chain.size() - 1), p) <= 0) {
            chain.remove(chain.size() - 1);
        }
        chain.add(p);
    }

    private static Vertex[] seed(List<Vertex> hull) {
        if (hull.size() < 4) return null;
        MatOfPoint2f source = new MatOfPoint2f(hull.stream().map(Vertex::openCv).toArray(Point[]::new));
        MatOfPoint2f approximation = new MatOfPoint2f();
        try {
            double length = Imgproc.arcLength(source, true);
            for (int i = 0; i <= 30; i++) {
                Imgproc.approxPolyDP(source, approximation, (.005 + i * .0025) * length, true);
                if (approximation.total() == 4) {
                    return canonical(Arrays.stream(approximation.toArray())
                            .map(p -> new Vertex((float) p.x, (float) p.y)).toArray(Vertex[]::new));
                }
            }
            return null;
        } finally {
            approximation.release();
            source.release();
        }
    }

    private static Line fitSide(List<Vertex> points, Vertex start, Vertex end) {
        float dx = end.x - start.x, dy = end.y - start.y;
        float length = (float) Math.hypot(dx, dy);
        if (length < 4) return null;
        dx /= length;
        dy /= length;
        List<Vertex> candidates = new ArrayList<>();
        for (Vertex p : points) {
            float ox = p.x - start.x, oy = p.y - start.y;
            float along = ox * dx + oy * dy;
            if (along >= .10 * length && along <= .90 * length
                    && Math.abs(-ox * dy + oy * dx) <= Math.max(1.5, .14 * length)) {
                candidates.add(p);
            }
        }
        if (candidates.size() < 3) return null;
        MatOfPoint2f xy = new MatOfPoint2f(candidates.stream().map(Vertex::openCv).toArray(Point[]::new));
        Mat fitted = new Mat();
        float[] line = new float[4];
        try {
            Imgproc.fitLine(xy, fitted, Imgproc.DIST_HUBER, 0, .01, .01);
            fitted.get(0, 0, line);
        } finally {
            fitted.release();
            xy.release();
        }
        double nx = -line[1], ny = line[0];
        if (Math.abs(-nx * dy + ny * dx) < .95) return null;
        int inliers = 0;
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (Vertex p : candidates) {
            if (Math.abs((p.x - line[2]) * nx + (p.y - line[3]) * ny) <= 1) {
                float along = (p.x - start.x) * dx + (p.y - start.y) * dy;
                lo = Math.min(lo, along);
                hi = Math.max(hi, along);
                inliers++;
            }
        }
        return inliers < 3 ? null : new Line(nx, ny, nx * line[2] + ny * line[3], (hi - lo) / length);
    }

    private static Result support(float[] probability, float[] boundary, int width, int height,
                                  boolean[] component, List<Vertex> points, Vertex[] quad,
                                  Line[] lines, int area, double dominance) {
        int covered = 0, outside = 0;
        double confidence = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int j = y * width + x;
                boolean inside = true;
                for (int i = 0; i < 4; i++) {
                    Vertex a = quad[i], b = quad[(i + 1) % 4];
                    if ((b.x - a.x) * (y - (double) a.y) < (b.y - a.y) * (x - (double) a.x)) {
                        inside = false;
                        break;
                    }
                }
                if (component[j]) {
                    confidence += probability[j];
                    if (inside) covered++;
                } else if (inside) {
                    outside++;
                }
            }
        }
        double expansion = (double) outside / area, omitted = (double) (area - covered) / area;
        double[] continuity = new double[4], edge = boundary == null ? new double[0] : new double[4];
        boolean weak = false;
        for (int i = 0; i < 4; i++) {
            Vertex a = quad[i], b = quad[(i + 1) % 4];
            Line line = lines[i];
            float dx = b.x - a.x, dy = b.y - a.y;
            int samples = Math.max(4, (int) Math.rint((float) Math.hypot(dx, dy)));
            int near = 0;
            double edgeSum = 0;
            for (int k = 0; k < samples; k++) {
                double t = .18 + .64 * k / (samples - 1);
                double x = a.x + t * dx, y = a.y + t * dy;
                for (Vertex p : points) {
                    double ox = x - p.x, oy = y - p.y;
                    if (ox * ox + oy * oy <= 2.25) {
                        near++;
                        break;
                    }
                }
                if (boundary != null) {
                    float best = Float.NEGATIVE_INFINITY;
                    for (int step = -2; step <= 2; step++) {
                        int px = Math.max(0, Math.min(width - 1, (int) Math.rint(x + step * line.nx)));
                        int py = Math.max(0, Math.min(height - 1, (int) Math.rint(y + step * line.ny)));
                        best = Math.max(best, boundary[py * width + px]);
                    }
                    edgeSum += best;
                }
            }
            continuity[i] = (double) near / samples;
            if (boundary != null) edge[i] = edgeSum / samples;
            weak |= line.coverage < .45 || continuity[i] < .70 || boundary != null && edge[i] < .25;
        }
        List<String> reasons = new ArrayList<>();
        if (dominance < .95) reasons.add("fragmented_mask");
        if (weak) reasons.add("weak_side");
        if (expansion > .08 || omitted > .06) reasons.add("poor_mask_agreement");
        if (confidence / area < .8) reasons.add("low_mask_confidence");
        return new Result(canonical(quad), reasons, dominance, expansion, omitted, continuity, edge);
    }

    static Vertex[] toSource(Vertex[] grid, double[] sourceToInput) {
        double a = sourceToInput[0], b = sourceToInput[1], c = sourceToInput[3], d = sourceToInput[4];
        double det = a * d - b * c;
        if (!Double.isFinite(det) || Math.abs(det) < 1e-12) throw new IllegalArgumentException("Singular ROI");
        Vertex[] mapped = new Vertex[4];
        for (int i = 0; i < 4; i++) {
            double x = 4 * grid[i].x + 1.5 - sourceToInput[2];
            double y = 4 * grid[i].y + 1.5 - sourceToInput[5];
            mapped[i] = b == 0 && c == 0 ? new Vertex((float) (x / a), (float) (y / d))
                    : new Vertex((float) ((d * x - b * y) / det), (float) ((a * y - c * x) / det));
        }
        return canonical(mapped);
    }

    static boolean validSourceQuad(Vertex[] points, int width, int height) {
        if (points == null || !positiveConvex(points) || signedArea(points) < 4) return false;
        for (Vertex p : points) {
            if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || p.x < -1e-4 || p.y < -1e-4
                    || p.x > width - 1 + 1e-4 || p.y > height - 1 + 1e-4) return false;
        }
        return true;
    }

    private static Vertex[] canonical(Vertex[] original) {
        Vertex[] q = original.clone();
        if (signedArea(q) < 0) {
            for (int i = 0; i < q.length; i++) q[i] = original[q.length - 1 - i];
        }
        float[][] direction = new float[4][2];
        for (int i = 0; i < 4; i++) {
            Vertex a = q[i], b = q[(i + 1) % 4];
            float dx = b.x - a.x, dy = b.y - a.y;
            float length = Math.max(1e-5f, (float) Math.hypot(dx, dy));
            direction[i] = new float[]{dx / length, dy / length};
        }
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float score = direction[i][0] + direction[(i + 1) % 4][1]
                    - direction[(i + 2) % 4][0] - direction[(i + 3) % 4][1];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        Vertex[] ordered = new Vertex[4];
        for (int i = 0; i < 4; i++) ordered[i] = q[(i + best) % 4];
        return ordered;
    }

    private static double signedArea(Vertex[] q) {
        double area = 0;
        for (int i = 0; i < q.length; i++) {
            Vertex a = q[i], b = q[(i + 1) % q.length];
            area += (double) a.x * b.y - (double) a.y * b.x;
        }
        return area * .5;
    }

    private static boolean positiveConvex(Vertex[] q) {
        for (int i = 0; i < 4; i++) if (!(turn(q[i], q[(i + 1) % 4], q[(i + 2) % 4]) > 0)) return false;
        return true;
    }

    private static double turn(Vertex a, Vertex b, Vertex c) {
        return (b.x - (double) a.x) * (c.y - (double) a.y) - (b.y - (double) a.y) * (c.x - (double) a.x);
    }

    private static Result reject(String reason, double dominance) {
        return new Result(null, Collections.singletonList(reason), dominance, Double.NaN, Double.NaN, new double[0], new double[0]);
    }
}


