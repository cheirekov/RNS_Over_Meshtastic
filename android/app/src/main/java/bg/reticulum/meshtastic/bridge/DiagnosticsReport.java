package bg.reticulum.meshtastic.bridge;

/** Builds a secret-free, paste-ready field report from the visible bridge status. */
final class DiagnosticsReport {
    private DiagnosticsReport() {}

    static String create(
            String capturedAt, String versionName, int versionCode,
            String device, String androidVersion, String status) {
        return "Reticulum Meshtastic Bridge diagnostics"
                + "\ncaptured_at: " + capturedAt
                + "\napp: " + versionName + " (" + versionCode + ")"
                + "\ndevice: " + device
                + "\nandroid: " + androidVersion
                + "\n---\n" + (status == null || status.isBlank() ? "status unavailable" : status.trim())
                + "\n";
    }
}
