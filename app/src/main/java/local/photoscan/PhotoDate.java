package local.photoscan;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

final class PhotoDate {
    private PhotoDate() {}

    static boolean looksLikeCalendarDate(String date) {
        return date.matches("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}");
    }

    static boolean isCalendarDate(String date) {
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || date.startsWith("0000")) return false;
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
