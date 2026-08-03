package app.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GptSovitsServerProcessTest {
    @Test
    void parsesQuotedCommandArguments() {
        List<String> arguments = GptSovitsServerProcess.parseCommandLine(
                "/bin/zsh -lc \"cd '/Users/example/GPT SoVITS' && python api_v2.py\""
        );

        assertEquals(List.of(
                "/bin/zsh",
                "-lc",
                "cd '/Users/example/GPT SoVITS' && python api_v2.py"
        ), arguments);
    }

    @Test
    void preservesEscapedSpaces() {
        List<String> arguments = GptSovitsServerProcess.parseCommandLine(
                "/Users/example/GPT\\ SoVITS/start-api.sh --port 9880"
        );

        assertEquals(List.of(
                "/Users/example/GPT SoVITS/start-api.sh",
                "--port",
                "9880"
        ), arguments);
    }

    @Test
    void rejectsUnclosedQuote() {
        VoiceSynthesisException exception = assertThrows(
                VoiceSynthesisException.class,
                () -> GptSovitsServerProcess.parseCommandLine("\"/tmp/start api.sh")
        );

        assertTrue(exception.getMessage().contains("未闭合引号"));
    }
}
