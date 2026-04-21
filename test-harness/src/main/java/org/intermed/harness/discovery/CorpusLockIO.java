package org.intermed.harness.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON persistence for {@link CorpusLock}.
 */
public final class CorpusLockIO {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    public Path write(CorpusLock lock, Path outputFile) throws IOException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.writeString(outputFile, GSON.toJson(lock));
        return outputFile;
    }

    public CorpusLock read(Path inputFile) throws IOException {
        return GSON.fromJson(Files.readString(inputFile), CorpusLock.class);
    }
}
