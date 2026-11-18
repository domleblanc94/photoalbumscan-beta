package local.photoscan;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Only the state executor submits or receives work. The detector never sees session state. */
final class PageProcessingQueue {
    interface Detector { Detection detect(File original) throws Exception; }
    interface Completion { void complete(Request request, Detection detection); }

    static final class Request {
        final String sourceId;
        final long revision;
        final File original;
        final long queuedElapsed;
        final String requestedInstanceBoundaryPolicy;
        long startedElapsed = -1;
        long finishedElapsed = -1;

        Request(ScanSession.Source source, File original) { this(source,original,null); }
        Request(ScanSession.Source source,File original,String requestedInstanceBoundaryPolicy) {
            this.requestedInstanceBoundaryPolicy=requestedInstanceBoundaryPolicy;
            sourceId = source.id;
            revision = source.revision;
            this.original = original;
            queuedElapsed = source.queuedElapsed;
        }

        boolean stillApplies(ScanSession.Source source) {
            return sameRevision(source) && source.pending();
        }

        boolean sameRevision(ScanSession.Source source) {
            return source != null && sourceId.equals(source.id) && source.revision == revision;
        }
    }

    static final class Detection {
        final List<float[]> corners;
        final List<String> cropReasons;
        final boolean reviewRequired;
        final List<String> warnings;
        final int unoutlined;
        final org.json.JSONObject boundaryDiagnostics;
        final org.json.JSONObject instanceBoundaryDiagnostics;

        Detection(List<float[]> corners, boolean reviewRequired) {
            this(corners, java.util.Collections.nCopies(corners.size(), ""), reviewRequired,
                    java.util.Collections.emptyList(), 0);
        }

        Detection(List<float[]> corners, List<String> cropReasons, boolean reviewRequired,
                  List<String> warnings, int unoutlined) {
            this(corners,cropReasons,reviewRequired,warnings,unoutlined,null);
        }
        Detection(List<float[]> corners,List<String> cropReasons,boolean reviewRequired,
                  List<String> warnings,int unoutlined,org.json.JSONObject boundaryDiagnostics) {
            this(corners,cropReasons,reviewRequired,warnings,unoutlined,boundaryDiagnostics,null);
        }
        Detection(List<float[]> corners,List<String> cropReasons,boolean reviewRequired,List<String> warnings,int unoutlined,
                  org.json.JSONObject boundaryDiagnostics,org.json.JSONObject instanceBoundaryDiagnostics) {
            this.instanceBoundaryDiagnostics=instanceBoundaryDiagnostics;
            this.corners = corners;
            this.cropReasons = cropReasons;
            this.warnings = warnings;
            this.reviewRequired = reviewRequired;
            this.unoutlined = unoutlined;
            this.boundaryDiagnostics=boundaryDiagnostics;
        }
    }

    private final Executor state;
    private final Executor worker;
    private final Detector detector;
    private boolean inFlight;

    PageProcessingQueue(Executor state, Executor worker, Detector detector) {
        this.state = state;
        this.worker = worker;
        this.detector = detector;
    }

    boolean inFlight() { return inFlight; }

    void submit(Request request, Completion completion) { submit(request,detector,completion); }
    void submit(Request request,Detector scopedDetector,Completion completion) {
        submitTask(() -> {
            request.startedElapsed = android.os.SystemClock.elapsedRealtime();
            try {
                return scopedDetector.detect(request.original);
            } finally {
                request.finishedElapsed = android.os.SystemClock.elapsedRealtime();
            }
        }, result -> completion.complete(request, result));
    }

    <T> void submitTask(Callable<T> task, Consumer<T> completion) {
        if (inFlight) throw new IllegalStateException("A page is already processing.");
        inFlight = true;
        worker.execute(() -> {
            T result;
            try {
                result = task.call();
            } catch (Exception | LinkageError | OutOfMemoryError failure) {
                result = null;
            }
            T finished = result;
            state.execute(() -> {
                inFlight = false;
                completion.accept(finished);
            });
        });
    }
}
