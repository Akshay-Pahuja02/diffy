package ai.diffy.lifter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class StringLifter {

    private static final Pattern HTML_REGEX = Pattern.compile("<(\"[^\"]*\"|'[^']*'|[^'\">])*>");

    public static Object lift(String string) {
        if (string == null) return null;
        // Try to parse as JSON first
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "json");
            m.put("value", JsonLifter.lift(JsonLifter.decode(string)));
            return new FieldMap(m);
        } catch (Exception e) {
            // Not JSON — check for HTML (skip large/binary bodies to avoid regex stack overflows)
            if (string.length() <= 8192 && string.indexOf('<') >= 0 && HTML_REGEX.matcher(string).find()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "html");
                m.put("value", HtmlLifter.lift(HtmlLifter.decode(string)));
                return new FieldMap(m);
            }
            return string;
        }
    }
}
