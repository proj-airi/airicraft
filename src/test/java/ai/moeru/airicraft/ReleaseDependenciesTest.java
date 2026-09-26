package ai.moeru.airicraft;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReleaseDependenciesTest {
    @TempDir
    Path directory;
    private URLClassLoader loader;
    private ClassLoader previousContextLoader;

    @BeforeEach
    void loadOnlyLibrariesRegisteredInReleaseJar() throws Exception {
        var urls = new ArrayList<URL>();
        try (var jar = new JarFile(System.getProperty("airicraft.releaseJar"));
             var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
            var metadata = JsonParser.parseReader(reader).getAsJsonObject();
            for (var nested : metadata.getAsJsonArray("jars")) {
                var name = nested.getAsJsonObject().get("file").getAsString();
                var target = directory.resolve(Path.of(name).getFileName());
                try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                    Files.copy(input, target);
                }
                urls.add(target.toUri().toURL());
            }
        }
        // No Gradle/test/Minecraft libraries may mask an absent bundled dependency.
        loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        previousContextLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
    }

    @AfterEach
    void closeLibraries() throws Exception {
        if (loader != null) {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
            loader.close();
        }
    }

    @Test
    void bundledSnakeYamlParsesConfiguration() throws Exception {
        var yaml = loader.loadClass("org.yaml.snakeyaml.Yaml");
        var parsed = yaml.getMethod("load", String.class).invoke(yaml.getConstructor().newInstance(), "enabled: true");
        assertEquals(Map.of("enabled", true), parsed);
    }

    @Test
    void bundledTelemetryCreatesSdkAndHttpExporter() throws Exception {
        var sdkType = loader.loadClass("io.opentelemetry.sdk.OpenTelemetrySdk");
        var sdkBuilder = sdkType.getMethod("builder").invoke(null);
        try (var sdk = (AutoCloseable) sdkBuilder.getClass().getMethod("build").invoke(sdkBuilder)) {
            assertNotNull(sdk);
            var exporterType = loader.loadClass("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter");
            var exporterBuilder = exporterType.getMethod("builder").invoke(null);
            try (var exporter = (AutoCloseable) exporterBuilder.getClass().getMethod("build").invoke(exporterBuilder)) {
                assertNotNull(exporter);
            }
        }
    }
}
