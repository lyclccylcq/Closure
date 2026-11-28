package app.ui.chat;

import app.billing.OpenAiFundsFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named="closure.nativeRestartAdminTest", matches="true")
class PersistentAdminRestartWindowTest {
    @TempDir Path directory;

    @Test void savedKeyIsUsedByACompletelyNewWorkbenchProcessWithoutReentry() throws Exception {
        String namespace = "com.closure.tests.restart." + UUID.randomUUID().toString().replace("-", "");
        Path evidence = Path.of(System.getProperty("closure.adminRestartEvidence", directory.toString()));
        Files.createDirectories(evidence);
        try {
            for (String mode : List.of("save", "restore")) {
                Path log = evidence.resolve("admin-restart-" + mode + ".log");
                String modules = System.getProperty("jdk.module.path");
                assertNotNull(modules, "Run the native fixture using this project's modular Maven test setup");
                String tests = Path.of(PersistentAdminRestartProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djava.awt.headless=false", "--module-path", modules, "--add-modules", "ALL-MODULE-PATH,jdk.httpserver",
                        "--patch-module", "com.example.closure=" + tests, "--add-reads", "com.example.closure=ALL-UNNAMED",
                        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        "-m", "com.example.closure/" + PersistentAdminRestartProbe.class.getName(), mode, namespace, evidence.toString());
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
                builder.environment().entrySet().removeIf(entry -> entry.getKey().matches("(?i).*(TOKEN|SECRET|PASSWORD|API_KEY|CREDENTIAL).*" )
                        || (entry.getKey().matches("(?i)^(CLOSURE_|OPENAI_|ANTHROPIC_|FISH_).*" )
                        && !entry.getKey().equals("CLOSURE_PET_ASSETS_DIRECTORY")));
                Process process = builder.start();
                try { assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Synthetic restart child timed out: " + mode); }
                finally { if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
                String output = Files.readString(log);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("CLOSURE_ADMIN_RESTART_CHILD_PASS mode=" + mode), output);
            }
            System.out.println("CLOSURE_ADMIN_RESTART_NATIVE_PASS independentJvms=2 realKeychain=true realWorkbench=true localHttp=true savedState=true noReentry=true clear=true noModelCalls=true");
        } finally { OpenAiFundsFixture.clearSyntheticKeychain(namespace); }
    }
}
