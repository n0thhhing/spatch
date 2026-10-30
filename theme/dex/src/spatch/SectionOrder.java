package spatch;

import java.util.Comparator;

/** Orders the settings list of Home sections by title. */
final class SectionOrder implements Comparator<String[]> {
    public int compare(String[] a, String[] b) {
        return a[1].compareToIgnoreCase(b[1]);
    }
}
