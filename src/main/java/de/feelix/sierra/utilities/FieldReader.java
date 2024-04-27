package de.feelix.sierra.utilities;

/**
 * Utility class for determining the readability of a given input string.
 */
public class FieldReader {

    /**
     * Determines if the given input string is readable.
     *
     * @param input the input string to check for readability
     * @return true if the input string is readable, false otherwise
     */
    public static boolean isReadable(String input) {
        for (char c : input.toCharArray()) {
            if (Character.isAlphabetic(c) || Character.isDigit(c) || !Character.isISOControl(c)) {
                continue;
            }
            return true;
        }
        return false;
    }
}
