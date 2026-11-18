/*
 * Float hull traversal adapted from OpenCV 4.13.0, modules/imgproc/src/convhull.cpp.
 * Copyright (C) 2000, Intel Corporation, all rights reserved.
 * Third party copyrights are property of their respective owners.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * - The name of Intel Corporation may not be used to endorse or promote products
 *   derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL INTEL CORPORATION OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package local.photoscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import local.photoscan.PictureQuadFit.Vertex;

/** Float contour vertices in OpenCV's counterclockwise point-output order. */
final class FloatContourHull {
    private record Point(Vertex value, int index) {}

    private FloatContourHull() {}

    static List<Vertex> points(List<Vertex> input) {
        int count = input.size();
        if (count < 2) return input;
        Point[] sorted = new Point[count];
        for (int i = 0; i < count; i++) sorted[i] = new Point(input.get(i), i);
        Arrays.sort(sorted, Comparator.comparingDouble((Point p) -> p.value.x())
                .thenComparingDouble(p -> p.value.y()).thenComparingInt(Point::index));
        int last = count - 1, minY = 0, maxY = 0;
        if (samePosition(sorted[0], sorted[last])) return Collections.singletonList(input.get(0));
        for (int i = 1; i < count; i++) {
            if (sorted[i].value.y() < sorted[minY].value.y()) minY = i;
            if (sorted[i].value.y() > sorted[maxY].value.y()) maxY = i;
        }

        int[] upperRight = chain(sorted, last, maxY, -1, -1);
        int[] upperLeft = chain(sorted, 0, maxY, -1, 1);
        int[] lowerLeft = chain(sorted, 0, minY, 1, -1);
        int[] lowerRight = chain(sorted, last, minY, 1, 1);
        int upperProbe = upperLeft.length > 2 ? upperLeft[1]
                : upperRight.length > 2 ? upperRight[upperRight.length - 2] : -1;
        int lowerProbe = lowerLeft.length > 2 ? lowerLeft[1]
                : lowerLeft.length + lowerRight.length > 2 ? lowerRight[2 - lowerLeft.length] : -1;
        boolean collinear = upperProbe >= 0 && lowerProbe >= 0
                && samePosition(sorted[upperProbe], sorted[lowerProbe]);
        List<Point> ring = new ArrayList<>();
        append(ring, sorted, upperRight, upperRight.length, false);
        append(ring, sorted, upperLeft, upperLeft.length, true);
        append(ring, sorted, lowerLeft, collinear ? Math.min(2, lowerLeft.length) : lowerLeft.length, false);
        append(ring, sorted, lowerRight, collinear ? Math.min(2, lowerRight.length) : lowerRight.length, true);
        rotateMonotoneInputOrder(ring);
        List<Vertex> vertices = new ArrayList<>(ring.size());
        for (Point point : ring) vertices.add(point.value);
        return vertices;
    }

    private static int[] chain(Point[] points, int start, int end, int verticalSign, int turnSign) {
        if (samePosition(points[start], points[end])) return new int[]{start};
        int step = end > start ? 1 : -1;
        int previous = start, current = start + step, next = current + step;
        int[] stack = new int[points.length + 2];
        int size = 3;
        stack[0] = previous;
        stack[1] = current;
        stack[2] = next;
        int stop = end + step;
        while (next != stop) {
            Vertex a = points[previous].value, b = points[current].value, c = points[next].value;
            float by = c.y() - b.y();
            if (sign(by) == verticalSign) {
                next += step;
                stack[size - 1] = next;
                continue;
            }
            float ax = b.x() - a.x(), ay = b.y() - a.y(), bx = c.x() - b.x();
            double aLength = Math.sqrt((double) ax * ax + (double) ay * ay);
            double bLength = Math.sqrt((double) bx * bx + (double) by * by);
            double aScale = aLength > 0 ? 1 / aLength : 0;
            double bScale = bLength > 0 ? 1 / bLength : 0;
            ax = (float) (ax * aScale);
            ay = (float) (ay * aScale);
            bx = (float) (bx * bScale);
            by = (float) (by * bScale);
            double turn = (double) ay * bx - (double) ax * by;
            if (sign(turn) == turnSign && (ax != 0 || ay != 0)) {
                previous = current;
                current = next;
                next += step;
                stack[size++] = next;
            } else if (previous == start) {
                current = next;
                next += step;
                stack[1] = current;
                stack[2] = next;
            } else {
                stack[size - 2] = next;
                current = previous;
                previous = stack[size - 4];
                size--;
            }
        }
        return Arrays.copyOf(stack, size - 1);
    }

    private static void append(List<Point> out, Point[] sorted, int[] chain, int count, boolean reverse) {
        for (int i = 0; i < count - 1; i++) out.add(sorted[chain[reverse ? count - 1 - i : i]]);
    }

    private static void rotateMonotoneInputOrder(List<Point> ring) {
        int size = ring.size();
        if (size < 3) return;
        int min = 0, max = 0;
        for (int i = 1; i < size; i++) {
            if (ring.get(i).index < ring.get(min).index) min = i;
            if (ring.get(i).index > ring.get(max).index) max = i;
        }
        boolean ascending = (max + 1) % size == min;
        if (!ascending && (min + 1) % size != max) return;
        int start = ascending ? min : max;
        for (int i = 0; i < size - 1; i++) {
            int previous = ring.get((start + i) % size).index;
            int next = ring.get((start + i + 1) % size).index;
            if ((previous < next) != ascending) return;
        }
        Collections.rotate(ring, -start);
    }

    private static boolean samePosition(Point first, Point second) {
        return first.value.x() == second.value.x() && first.value.y() == second.value.y();
    }

    private static int sign(double value) {
        return value > 0 ? 1 : value < 0 ? -1 : 0;
    }
}
