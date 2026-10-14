package sqlens.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** Largest integer a JavaScript number holds exactly. */
    private static final long JS_SAFE_INTEGER = (1L << 53) - 1;

    private Json() {
    }

    static void send(HttpServletResponse response, int status, Object body) throws IOException {
        prepare(response, status);
        MAPPER.writeValue(response.getOutputStream(), body);
    }

    static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        send(response, status, Map.of("error", message));
    }

    static void prepare(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
    }

    /**
     * Writes a converted cell value. Numbers that JavaScript would round
     * (big longs, high precision decimals) are written as strings.
     */
    static void writeValue(JsonGenerator gen, Object value) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof Boolean b) {
            gen.writeBoolean(b);
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            gen.writeNumber(((Number) value).intValue());
        } else if (value instanceof Long l) {
            if (Math.abs(l) <= JS_SAFE_INTEGER) {
                gen.writeNumber(l);
            } else {
                gen.writeString(l.toString());
            }
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d)) {
                gen.writeNumber(d);
            } else {
                gen.writeString(Double.toString(d));
            }
        } else if (value instanceof BigInteger bi) {
            if (bi.bitLength() <= 53) {
                gen.writeNumber(bi.longValue());
            } else {
                gen.writeString(bi.toString());
            }
        } else if (value instanceof BigDecimal bd) {
            BigDecimal stripped = bd.stripTrailingZeros();
            if (stripped.precision() <= 15) {
                gen.writeNumber(bd.toPlainString());
            } else {
                gen.writeString(bd.toPlainString());
            }
        } else if (value instanceof Number n) {
            gen.writeString(n.toString());
        } else {
            gen.writeString(value.toString());
        }
    }
}
