package local.photoscan;

import static org.junit.Assert.*;

import ai.onnxruntime.OrtException;



import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;
import local.photoscan.PictureQuadFit.Vertex;
import org.junit.Test;

public class PictureFallbackAreaTest {
    @Test public void quarterAreaIsInclusiveWithoutRoundingSourceCoordinates() {
        var proposal = rectangle(0, 0, 100, 100);
        var equal = rectangle(10, 10, 60, 60);
        var below = rectangle(10, 10, Math.nextDown(60f), 60);
        assertTrue(PictureContentDetector.meetsPrimaryArea(equal, proposal));
        assertFalse(PictureContentDetector.meetsPrimaryArea(below, proposal));
        assertFalse(PictureContentDetector.meetsPrimaryArea(null, proposal));
        assertSame(equal, PictureContentDetector.acceptedFallback(equal, proposal, 101, 101));
        assertNull(PictureContentDetector.acceptedFallback(below, proposal, 101, 101));
        assertNull(PictureContentDetector.acceptedFallback(null, proposal, 101, 101));
    }

    @Test public void rawProposalCanExtendPastSourceButFallbackCannot() {
        var proposal = rectangle(-1, -1, 99, 99);
        var tinyOutside = rectangle(-.00001f, 0, 70, 70);
        var exactBound = rectangle(50, 50, 100, 100);
        assertTrue(PictureContentDetector.eligibleFallbackProposal(proposal));
        assertTrue(PictureContentDetector.meetsPrimaryArea(tinyOutside, proposal));
        assertNull(PictureContentDetector.acceptedFallback(tinyOutside, proposal, 101, 101));
        assertSame(exactBound, PictureContentDetector.acceptedFallback(exactBound, proposal, 101, 101));
    }

    @Test public void versionTwoAcceptsOneTenthEqualityAndStillTriggersBelowOneQuarter() throws Exception {
        var proposal = rectangle(0, 0, 100, 100);
        var tenth = rectangle(10, 10, 30, 60);
        var below = rectangle(10, 10, Math.nextDown(30f), 60);
        assertSame(tenth, PictureContentDetector.acceptedFallback(tenth, proposal, 101, 101,
                PictureContentDetector.MIXED_FALLBACK_V2));
        assertNull(PictureContentDetector.acceptedFallback(below, proposal, 101, 101,
                PictureContentDetector.MIXED_FALLBACK_V2));
        assertNull(PictureContentDetector.acceptedFallback(tenth, proposal, 101, 101,
                PictureContentDetector.MIXED_FALLBACK));
        assertThrows(IllegalArgumentException.class,
                () -> PictureContentDetector.acceptedFallback(tenth, proposal, 101, 101, "unknown"));
        var page = new PictureContentDetector.FallbackAttempts();
        var first = new PictureContentDetector.Refined(tenth, "");
        var second = new PictureContentDetector.Refined(rectangle(0, 0, 40, 40), "weak_side");
        assertSame(second, page.runIfNeeded(first, proposal, () -> second));
    }

    @Test public void degenerateAndNonfiniteProposalsCannotTriggerTheFallback() {
        var line = rectangle(0, 0, 0, 100);
        var nonfinite = rectangle(0, 0, Float.NaN, 100);
        var crossed = rectangle(0, 0, 100, 100);
        Vertex swap = crossed[1];
        crossed[1] = crossed[2];
        crossed[2] = swap;
        assertFalse(PictureContentDetector.eligibleFallbackProposal(line));
        assertFalse(PictureContentDetector.eligibleFallbackProposal(nonfinite));
        assertFalse(PictureContentDetector.eligibleFallbackProposal(crossed));
        assertFalse(PictureContentDetector.meetsPrimaryArea(crossed, line));
    }

    @Test public void secondaryLoadOrInferenceFailureLeavesAnExplicitNullAndDisablesThisPageOnly() throws Exception {
        var primary = rectangle(0, 0, 100, 100);
        var good = rectangle(0, 0, 80, 80);
        for (Exception failure : new Exception[]{new IOException("load failed"), new OrtException("inference failed")}) {
            var page = new PictureContentDetector.FallbackAttempts();
            var failed = page.run(() -> {
                if (failure instanceof IOException io) throw io;
                throw (OrtException) failure;
            });
            assertNull(failed.quad());
            assertEquals("own_fallback_unavailable", failed.reason());
            var tiny = new PictureContentDetector.Refined(rectangle(0, 0, 10, 10), "");
            assertNull(page.runIfNeeded(tiny, primary,
                    () -> { throw new AssertionError("Secondary was called twice"); }).quad());
            var ordinary = new PictureContentDetector.Refined(good, "weak_side");
            assertNull(page.runIfNeeded(ordinary, primary,
                    () -> { throw new AssertionError("Valid primary reached fallback"); }));
            assertSame(good, ordinary.quad());
            assertEquals("weak_side", ordinary.reason());
            var next = new PictureContentDetector.FallbackAttempts();
            assertSame(good, next.run(() -> new PictureContentDetector.Refined(good, "")).quad());
        }
    }

    @Test public void interruptionCancellationAndMemoryFailurePropagate() {
        var page = new PictureContentDetector.FallbackAttempts();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> page.run(() -> { throw new AssertionError("Interrupted call entered secondary"); }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        var cancelled = new IOException(new CancellationException());
        assertSame(cancelled, assertThrows(IOException.class, () -> page.run(() -> { throw cancelled; })));
        var oom = new OutOfMemoryError();
        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> page.run(() -> { throw oom; })));
    }

    private static Vertex[] rectangle(float left, float top, float right, float bottom) {
        return new Vertex[]{new Vertex(left, top), new Vertex(right, top),
                new Vertex(right, bottom), new Vertex(left, bottom)};
    }
}
