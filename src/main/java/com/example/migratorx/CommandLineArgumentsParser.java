package com.example.migratorx;

import org.apache.commons.cli.*;

public class CommandLineArgumentsParser {

    private final String[] args;
    private final Options options = new Options();

    public CommandLineArgumentsParser(String[] args) {
        this.args = args;
        addDirectMigrationOptions();
        addJsonMigrationOptions();
    }

    public CommandLine parse() {
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("migratorx", options);
            System.exit(1);
            return null;
        }

        return cmd;
    }

    private void addDirectMigrationOptions() {
        OptionGroup directGroup = new OptionGroup();
        directGroup.addOption(new Option("i", "uri", true, "URI of the database"));
        directGroup.addOption(new Option("u", "username", true, "Username for the database"));
        directGroup.addOption(new Option("p", "password", true, "Password for the database"));
        directGroup.addOption(new Option("m", "migrationScriptLocation", true, "Location of the migration script"));
        directGroup.setRequired(true);
        options.addOptionGroup(directGroup);
    }

    private void addJsonMigrationOptions() {
        OptionGroup jsonGroup = new OptionGroup();
        jsonGroup.addOption(new Option("j", "json", true, "JSON file containing migration tasks"));
        jsonGroup.addOption(new Option("w", "workers", true, "Number of workers to use for migration"));
        jsonGroup.setRequired(true);
        options.addOptionGroup(jsonGroup);
    }
}
