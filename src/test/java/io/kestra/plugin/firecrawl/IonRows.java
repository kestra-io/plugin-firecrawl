package io.kestra.plugin.firecrawl;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

final class IonRows {
    private IonRows() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> read(RunContext runContext, URI uri) throws IOException {
        var rows = new ArrayList<Map<String, Object>>();

        try (var input = runContext.storage().getFile(uri)) {
            FileSerde.read(input, row -> rows.add((Map<String, Object>) row));
        }

        return rows;
    }
}
