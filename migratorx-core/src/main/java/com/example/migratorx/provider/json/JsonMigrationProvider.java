package com.example.migratorx.provider.json;

import com.example.migratorx.provider.MigrationTask;
import com.example.migratorx.provider.MigrationProvider;
import com.example.migratorx.utils.json.JsonUtil;

import java.io.File;
import java.util.List;

public class JsonMigrationProvider implements MigrationProvider {

    private final File jsonFile;

    public JsonMigrationProvider(File jsonFile) {
        this.jsonFile = jsonFile;
    }

    public JsonMigrationProvider(String jsonFile) {
        this.jsonFile = new File(jsonFile);
    }

    @Override
    public List<MigrationTask> get() {
       return JsonUtil.fromJsonFile(jsonFile, MigrationTask[].class);
    }
}
