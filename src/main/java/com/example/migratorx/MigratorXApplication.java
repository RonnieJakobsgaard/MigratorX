package com.example.migratorx;

import com.example.migratorx.migrator.centralized.CentralizedSchemaMigrator;
import com.example.migratorx.migrator.centralized.config.CentralizedSchemaMigratorProperties;
import com.example.migratorx.provider.MigrationProvider;
import com.example.migratorx.provider.direct.DirectMigrationProvider;
import com.example.migratorx.provider.json.JsonMigrationProvider;
import org.apache.commons.cli.CommandLine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;


@SpringBootApplication
@EnableConfigurationProperties
@EnableAutoConfiguration(exclude = {org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class})
public class MigratorXApplication {

	public static void main(String[] args) {

		CommandLineArgumentsParser parser = new CommandLineArgumentsParser(args);
		CommandLine cmd = parser.parse();

		MigrationProvider provider;
		CentralizedSchemaMigratorProperties properties = null;

		if (cmd.hasOption("i")) {
			provider = new DirectMigrationProvider(cmd.getOptionValue("i"), cmd.getOptionValue("u"), cmd.getOptionValue("p"), cmd.getOptionValue("m"));
			properties = new CentralizedSchemaMigratorProperties(1);
		} else if (cmd.hasOption("j")) {
			provider = new JsonMigrationProvider(cmd.getOptionValue("j"));
			properties = new CentralizedSchemaMigratorProperties(Integer.parseInt(cmd.getOptionValue("w")));
		} else {
			throw new IllegalArgumentException("Either -i or -j option must be provided");
		}

		CentralizedSchemaMigrator migrator = new CentralizedSchemaMigrator(provider, properties);
		migrator.migrate();

		SpringApplication.run(MigratorXApplication.class, args);
	}
}
