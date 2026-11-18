package local.photoscan;

import java.util.ArrayList;
import java.util.List;

/** Frozen Dykstra projection of four fixed-normal line offsets. */
final class ConstrainedLineOffsets {
    private static final double MARGIN = .001;
    private static final double MAX_DELTA = 1.5;
    private static final int MAX_SWEEPS = 256;
    private static final double CONVERGENCE = 1e-9;
    private static final double FEASIBILITY = 1e-8;

    record Projection(PictureQuadFit.Vertex[] quad, double[] delta) {}
    private record Halfspace(double[] normal, double limit) {}

    private ConstrainedLineOffsets() {}

    static boolean validTransform(double[] a) {
        if (a == null || a.length != 6) return false;
        for (double value : a) if (!Double.isFinite(value)) return false;
        double det = a[0] * a[4] - a[1] * a[3];
        return Double.isFinite(det) && Math.abs(det) >= 1e-12;
    }

    static Projection project(double[][] normals, double[] offsets, double[] a,
                              int width, int height, int sourceWidth, int sourceHeight) {
        if (!validTransform(a)) return null;
        double[][][] grid = new double[4][2][4];
        for (int i = 0; i < 4; i++) {
            int previous = (i + 3) % 4;
            double[] p = normals[previous], q = normals[i];
            double det = p[0] * q[1] - p[1] * q[0];
            if (!Double.isFinite(det) || Math.abs(det) < .30) return null;
            grid[i][0][previous] = q[1] / det;
            grid[i][0][i] = -p[1] / det;
            grid[i][1][previous] = -q[0] / det;
            grid[i][1][i] = p[0] / det;
        }
        double det = a[0] * a[4] - a[1] * a[3];
        double ix = a[4] / det, iy = -a[1] / det;
        double jx = -a[3] / det, jy = a[0] / det;
        double sourceX = ix * (1.5 - a[2]) + iy * (1.5 - a[5]);
        double sourceY = jx * (1.5 - a[2]) + jy * (1.5 - a[5]);
        List<Halfspace> constraints = new ArrayList<>(40);
        for (double[][] vertex : grid) {
            bounds(constraints, vertex[0], 0, width);
            bounds(constraints, vertex[1], 0, height);
        }
        for (double[][] vertex : grid) {
            double[] x = new double[4], y = new double[4];
            for (int j = 0; j < 4; j++) {
                x[j] = (4 * ix) * vertex[0][j] + (4 * iy) * vertex[1][j];
                y[j] = (4 * jx) * vertex[0][j] + (4 * jy) * vertex[1][j];
            }
            bounds(constraints, x, sourceX, sourceWidth);
            bounds(constraints, y, sourceY, sourceHeight);
        }
        for (int i = 0; i < 4; i++) {
            double[] lower = new double[4], upper = new double[4];
            lower[i] = -1;
            upper[i] = 1;
            constraints.add(new Halfspace(lower, MAX_DELTA - offsets[i]));
            constraints.add(new Halfspace(upper, MAX_DELTA + offsets[i]));
        }
        double[] result = nearest(offsets, constraints);
        if (result == null) return null;
        double[] delta = new double[4];
        var quad = new PictureQuadFit.Vertex[4];
        for (int i = 0; i < 4; i++) {
            delta[i] = result[i] - offsets[i];
            if (Math.abs(delta[i]) > MAX_DELTA + FEASIBILITY) return null;
            quad[i] = new PictureQuadFit.Vertex((float) dot(grid[i][0], result), (float) dot(grid[i][1], result));
        }
        return new Projection(quad, delta);
    }

    private static void bounds(List<Halfspace> constraints, double[] row, double shift, int size) {
        double[] lower = new double[4];
        for (int i = 0; i < 4; i++) lower[i] = -row[i];
        constraints.add(new Halfspace(lower, shift - MARGIN));
        constraints.add(new Halfspace(row, size - 1 - MARGIN - shift));
    }

    private static double[] nearest(double[] original, List<Halfspace> constraints) {
        double[][] rows = new double[constraints.size()][4];
        double[] limits = new double[constraints.size()];
        for (int i = 0; i < rows.length; i++) {
            Halfspace constraint = constraints.get(i);
            double length = Math.sqrt(dot(constraint.normal, constraint.normal));
            if (!(length > 0) || !Double.isFinite(length) || !Double.isFinite(constraint.limit)) return null;
            for (int j = 0; j < 4; j++) rows[i][j] = constraint.normal[j] / length;
            limits[i] = constraint.limit / length;
        }
        double[] current = original.clone(), previous = new double[4], trial = new double[4];
        double[][] correction = new double[rows.length][4];
        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            System.arraycopy(current, 0, previous, 0, 4);
            for (int i = 0; i < rows.length; i++) {
                for (int j = 0; j < 4; j++) trial[j] = current[j] + correction[i][j];
                double violation = Math.max(0, dot(rows[i], trial) - limits[i]);
                for (int j = 0; j < 4; j++) {
                    current[j] = trial[j] - violation * rows[i][j];
                    correction[i][j] = trial[j] - current[j];
                }
            }
            double movement = 0;
            for (int j = 0; j < 4; j++) movement = Math.max(movement, Math.abs(current[j] - previous[j]));
            if (!(movement <= CONVERGENCE)) continue;
            boolean feasible = true;
            for (int i = 0; i < rows.length; i++) {
                if (!(dot(rows[i], current) - limits[i] <= FEASIBILITY)) {
                    feasible = false;
                    break;
                }
            }
            if (feasible) return current;
        }
        return null;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < 4; i++) sum += a[i] * b[i];
        return sum;
    }
}
