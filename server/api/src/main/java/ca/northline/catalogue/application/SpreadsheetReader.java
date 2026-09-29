package ca.northline.catalogue.application;

import java.util.List;
import java.util.Map;

/** Outbound port: reads the first sheet of an .xlsx or a .csv into rows keyed by the (lower-cased) header row. */
public interface SpreadsheetReader {

    /** Thrown for files that are neither a readable .xlsx nor a .csv. */
    final class UnreadableFile extends RuntimeException {
        public UnreadableFile(String message) {
            super(message);
        }
    }

    List<Map<String, String>> read(String fileName, byte[] bytes);
}
