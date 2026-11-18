package local.photoscan;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

/** Enforces the beta's offline telemetry policy before any model session is created. */
final class OfflineOrt {
    private OfflineOrt() {}

    static synchronized OrtEnvironment environment() throws OrtException {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        return environment;
    }
}