package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DiagnosticsReportTest {
    @Test public void createsStablePasteReadyReport() {
        assertEquals(
                "Reticulum Meshtastic Bridge diagnostics\n"
                        + "captured_at: 2026-08-14T12:34:56+03:00\n"
                        + "app: 0.1.17 (18)\n"
                        + "device: Google Pixel 6 Pro\n"
                        + "android: 16 (API 36)\n"
                        + "---\nradio ready\nqueue empty\n",
                DiagnosticsReport.create(
                        "2026-08-14T12:34:56+03:00", "0.1.17", 18,
                        "Google Pixel 6 Pro", "16 (API 36)",
                        "radio ready\nqueue empty\n"));
    }
}
