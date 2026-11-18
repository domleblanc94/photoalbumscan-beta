package local.photoscan;

import java.util.UUID;

final class CollectionExposureSet {
    static final int SIZE = 3;
    final String id = UUID.randomUUID().toString();
    final String policy;
    private int saved;
    private boolean stopped;

    CollectionExposureSet(String policy) { this.policy = policy; }

    static boolean samples(int nextOrdinal, boolean singleOnly) {
        return !singleOnly && nextOrdinal > 0 && (nextOrdinal - 1) % 4 == 0;
    }

    int saved() { return saved; }

    boolean hasNext() { return !stopped && saved < SIZE; }

    void stop() { stopped = true; }

    void committed() { saved++; }

    int nextEv() { return saved == 0 ? 0 : saved == 1 ? -1 : 1; }

    String nextRole() { return saved == 0 ? "normal" : saved == 1 ? "darker" : "brighter"; }
}
