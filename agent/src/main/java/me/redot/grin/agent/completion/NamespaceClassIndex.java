package me.redot.grin.agent.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class NamespaceClassIndex {

    private final Map<String, Set<String>> packages = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> classes = new ConcurrentHashMap<>();
    private final Set<Path> scannedPaths = ConcurrentHashMap.newKeySet();

    public void addPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!this.scannedPaths.add(normalized)) return;

        try {
            if (Files.isDirectory(normalized)) {
                this.scanDirectory(normalized);
            } else if (Files.isRegularFile(normalized)) {
                this.scanJar(normalized);
            }
        } catch (IOException | RuntimeException ignored) {}
    }

    public void addClassName(String binaryName) {
        if (binaryName != null
                && !binaryName.startsWith("[")
                && binaryName.indexOf('/') < 0) {
            this.addClassPath(binaryName.replace('.', '/') + ".class");
        }
    }

    public Set<String> packageChildren(String packageName) {
        return this.packages.getOrDefault(packageName, Collections.emptySet());
    }

    public Set<String> classChildren(String packageName) {
        return this.classes.getOrDefault(packageName, Collections.emptySet());
    }

    private void scanJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (!entry.isDirectory()) {
                    this.addClassPath(entry.getName());
                }
            }
        }
    }

    private void scanDirectory(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            entries.filter(Files::isRegularFile)
                    .map(other -> root.relativize(other).toString().replace('\\', '/'))
                    .forEach(this::addClassPath);
        }
    }

    private void addClassPath(String path) {
        if (!path.endsWith(".class") || path.startsWith("META-INF/")) return;

        String binaryPath = path.substring(0, path.length() - ".class".length());
        int lastSlash = binaryPath.lastIndexOf('/');
        String packagePath = lastSlash < 0 ? "" : binaryPath.substring(0, lastSlash);
        String simpleName = binaryPath.substring(lastSlash + 1);

        if (simpleName.equals("module-info")
                || simpleName.equals("package-info")
                || simpleName.indexOf('$') >= 0
                || !isIdentifier(simpleName)) {
            return;
        }

        String packageName = "";
        if (!packagePath.isEmpty()) {
            for (String segment : packagePath.split("/")) {
                if (!isIdentifier(segment)) return;
                this.packages.computeIfAbsent(packageName, ignored -> ConcurrentHashMap.newKeySet()).add(segment);
                packageName = packageName.isEmpty() ? segment : packageName + "." + segment;
            }
        }

        this.classes.computeIfAbsent(packageName, ignored -> ConcurrentHashMap.newKeySet()).add(simpleName);
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) return false;
        }
        return true;
    }
}