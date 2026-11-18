package local.photoscan;

import android.content.Context;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads the same notices shipped in the APK; never opens a network destination. */
final class OpenSourceLicenses {
    private OpenSourceLicenses() {}

    static String read(Context context) throws IOException {
        StringBuilder text = new StringBuilder();
        append(context, "THIRD_PARTY_NOTICES.txt", text);
        String[] names = context.getAssets().list("licenses");
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) append(context, "licenses/" + name, text);
        }
        return text.toString();
    }

    private static void append(Context context, String path, StringBuilder text) throws IOException {
        text.append("\n\n=== ").append(path).append(" ===\n\n");
        try (var reader = new InputStreamReader(context.getAssets().open(path), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            for (int count = reader.read(buffer); count != -1; count = reader.read(buffer)) {
                text.append(buffer, 0, count);
            }
        }
    }
}