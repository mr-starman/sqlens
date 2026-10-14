package sqlens.web;

import com.fasterxml.jackson.core.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void keepsNumbersJavaScriptCanHoldAndQuotesTheRest() throws Exception {
        assertEquals("42", write(42));
        assertEquals("9007199254740991", write(9007199254740991L));
        assertEquals("\"9007199254740993\"", write(9007199254740993L));
        assertEquals("1000", write(new BigDecimal("1E+3")));
        assertEquals("1.50", write(new BigDecimal("1.50")));
        assertEquals("\"12345678901234567890.12\"", write(new BigDecimal("12345678901234567890.12")));
        assertEquals("\"123456789012345678901234\"", write(new BigInteger("123456789012345678901234")));
        assertEquals("\"NaN\"", write(Double.NaN));
        assertEquals("null", write(null));
        assertEquals("true", write(true));
    }

    private static String write(Object value) throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = Json.MAPPER.getFactory().createGenerator(out)) {
            Json.writeValue(gen, value);
        }
        return out.toString();
    }
}
