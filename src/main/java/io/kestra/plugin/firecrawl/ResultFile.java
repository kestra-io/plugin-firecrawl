package io.kestra.plugin.firecrawl;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.firecrawl.models.Document;

import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;

/**
 * ION file in the working directory that receives one row per document as they arrive, then goes to the internal storage.
 */
final class ResultFile implements AutoCloseable {
    private final RunContext runContext;
    private final Path path;
    private final OutputStream output;
    private int count;

    private ResultFile(RunContext runContext, Path path, OutputStream output) {
        this.runContext = runContext;
        this.path = path;
        this.output = output;
    }

    static ResultFile create(RunContext runContext) throws IOException {
        var path = runContext.workingDir().createTempFile(".ion");
        return new ResultFile(runContext, path, new BufferedOutputStream(Files.newOutputStream(path), FileSerde.BUFFER_SIZE));
    }

    void write(List<Document> documents) throws IOException {
        for (var document : documents) {
            FileSerde.write(this.output, JacksonMapper.toMap(document));
            this.count++;
        }
    }

    int count() {
        return this.count;
    }

    URI upload() throws IOException {
        this.output.close();
        return this.runContext.storage().putFile(this.path.toFile());
    }

    @Override
    public void close() throws IOException {
        this.output.close();
    }
}
