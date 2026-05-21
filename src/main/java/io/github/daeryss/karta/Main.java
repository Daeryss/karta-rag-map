package io.github.daeryss.karta;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
        name = "karta",
        version = "karta 0.1.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        description = "RAG-based code cartographer for large Java codebases."
)
public class Main implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        System.out.println("karta v0.1.0-SNAPSHOT — skeleton only");
        System.out.println("subcommands coming: ingest | index | query | viz");
        return 0;
    }
}
