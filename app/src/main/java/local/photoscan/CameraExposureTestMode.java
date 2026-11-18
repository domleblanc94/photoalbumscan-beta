package local.photoscan;

final class CameraExposureTestMode {
    record Setting(int requestedEv, int index, double step) {
        double appliedEv() { return index * step; }
    }

    static final class RequestGate {
        private int token;
        private boolean pending;

        int begin() {
            pending = true;
            return ++token;
        }

        boolean finish(int request) {
            if (!pending || request != token) return false;
            pending = false;
            return true;
        }

        boolean pending() { return pending; }

        void clear() { pending = false; }
    }

    static Setting select(int requestedEv, int minIndex, int maxIndex, double step) {
        if (requestedEv < -1 || requestedEv > 1 || minIndex > maxIndex || !Double.isFinite(step) || step <= 0) return null;
        long nearest = requestedEv * Math.round(1.0 / step);
        int index = (int) Math.max(minIndex, Math.min(maxIndex, nearest));
        if (Integer.signum(index) != requestedEv) return null;
        return new Setting(requestedEv, index, step);
    }
}
