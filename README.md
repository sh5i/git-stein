# git-stein
[![Java CI](https://github.com/sh5i/git-stein/actions/workflows/main.yml/badge.svg)](https://github.com/sh5i/git-stein/actions/workflows/main.yml)
[![jitpack](https://jitpack.io/v/sh5i/git-stein.svg)](https://jitpack.io/#sh5i/git-stein)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/sh5i/git-stein/blob/main/LICENSE)

_git-stein_ is a general-purpose Java framework for rewriting Git repositories.
Users can use this framework to implement their desired history rewriting by customizing the default behavior of the framework.
Several [bundle applications](#bundle-apps) using this framework are also available; they are not only practical but also helpful in understanding how to use this framework.

## Requirements

- **Running**: Java 17 or later
- **Building**: Java 17 or later (required by Gradle 9)

> To run on Java 11, use the pre-built JAR from [v0.7.0](https://github.com/sh5i/git-stein/releases/tag/v0.7.0).


## Build and Run

Build:
```
$ git clone https://github.com/sh5i/git-stein.git
$ cd git-stein
$ ./gradlew executableJar
$ cp build/libs/git-stein.jar $(git --exec-path)/git-stein  # Add as a Git subcommand
```

Run command:
```
$ java -jar build/libs/git-stein.jar [options...]
$ build/libs/git-stein.jar [options...]   # In UNIX-like system, it is self-executable
$ git stein [options...]                  # When subcommand available
```

## Recipes

### Splitting and converting to cregit

Split Java files into method-level modules, then convert each to cregit format:
```
$ git stein path/to/source-repo -o path/to/target-repo \
  @historage-jdt --no-original --no-classes \
  @cregit --pattern='*.cjava' --ignore-case
```

### Using an external command

`@convert` supports three modes for delegating to external tools:

**Command mode** (default): writes the blob to a temporary file, runs the command, and collects all output files.
Use this when the command expects file arguments.
```
$ git stein path/to/repo -o path/to/out \
  @convert --cmd='prettier --write' --pattern='*.js'
```

**Filter mode** (`--filter`): pipes the blob to stdin and captures stdout.
Use this for stream-oriented tools.
```
$ git stein path/to/repo -o path/to/out \
  @convert --cmd='tr a-z A-Z' --filter --pattern='*.txt'
```

**Endpoint mode** (`--endpoint`): POSTs the blob to an HTTP API and uses the response.
Use this for remote services.
```
$ git stein path/to/repo -o path/to/out \
  @convert --endpoint=http://localhost:8080/convert --pattern='*.java'
```

### Writing a custom blob translator

Implement the `BlobTranslator` interface to define your own transformation.
The `BlobTranslator.of` factory creates one from a simple `String -> String` function:

```java
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;

// Uppercase all file contents
BlobTranslator upper = BlobTranslator.of(String::toUpperCase);
// Add a copyright header
BlobTranslator header = BlobTranslator.of(s -> "// Copyright 2024\n" + s);
```

For more control, implement `BlobTranslator` directly:
```java
public class MyTranslator implements BlobTranslator {
    @Override
    public AnyHotEntry rewriteBlobEntry(HotEntry entry, Context c) {
        if (!entry.getName().endsWith(".java")) {
            return entry;  // pass through non-Java files
        }
        return entry.update(transform(entry.getBlob()));
    }
}
```

## General Options
- `-o`, `--output=<path>`: Specify the destination repository path. If it is omitted, git-stein runs as _overwrite_ mode (rewriting the input repo).
- `-d`, `--duplicate`: Duplicate the source repository and overwrites it. **Requires `-o`**.
- `--clean`: Delete the target repository before applying the transformation if it exists. **Requires `-o`**.
- `--bare`: Treat that the specified repositories are bare.
- `-j`, `--jobs=<nthreads>`: Rewrites trees in parallel using `<nthreads>` threads (see [Parallel Rewriting](#parallel-rewriting)). If the number of threads is omitted (just `-j` is given), the number of available processors is used.
- `-n`, `--dry-run`: Do not actually modify the target repository.
- `--stream-size-limit=<num>{,K,M,G}`: increase the stream size limit.
- `--no-notes`: Stop noting the source commit ID to the commits in the target repository (see [Notes](#notes)).
- `--no-pack`: Stop packing objects after transformation finished.
- `--alternates`: Share source objects via Git alternates to skip writing unchanged objects, which speeds up transformations where many objects are unchanged. The target repository will depend on the source's object store until repacked.
- `--no-composite`: Stop composing multiple blob translators (see [Chaining Commands](#chaining-commands)).
- `--extra-attributes`: Allow opportunity to rewrite the encoding and the signature fields in commits.
- `--cache`: Enable persistent entry caching (see [Caching](#caching)).
- `--mapping-mem=<num>{,K,M,G}`: Max memory for entry mapping cache. Default: 25% of max heap (see [Caching](#caching)).
- `--cmdpath=<path>:...`: Add packages for search for commands.
- `--log=<level>`: Specify log level (default: `INFO`).
- `-q`, `--quiet`: Quiet mode (same as `--log=ERROR`).
- `-v`, `--verbose`: Verbose mode (same as `--log=DEBUG`).
- `--help`: Show a help message and exit.
- `--version`: Print version information and exit.


In addition to general options, each app has its own options.


## Rewriting Mode
The git-stein supports three rewriting modes.
- _overwrite_ mode (`<source>`): given a source repository, rewriting it.
- _transform_ mode (`<source> -o <target>`): given source and target repositories, rewriting objects in the source repository and storing them in the target repository.
- _duplicate_ mode (`<source> -o <target> -d`): given a source repository and a path for the target repository, copying the source repository into the given path and applying overwrite mode to the target repository.


## Bundle Apps

### Blob Translators
_Blob translators_ provide a blob-to-blob(s) translations.

#### @historage
Generates a [Historage](https://github.com/hideakihata/git2historage)-like repository using [Universal Ctags](https://ctags.io/).
Options:
- `--ctags=<cmd>`: Location of executable `ctags` command. _Default: ctags_.
- `--no-original`: Exclude original files.
- `--no-original-ext`: Disuse original file extension.
- `--no-sig`: Stop using signature (parameters) for generating filenames.
- `--no-digest-sig`: Stop digesting signature.
- `--module=<kind>,...`: Specify module kinds to include.
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive matching for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @historage-jdt
Generates a [Historage](https://github.com/hideakihata/git2historage)-like repository from a Java-based project repository using [Eclipse-JDT](https://projects.eclipse.org/projects/eclipse.jdt).
Options:
- `--no-original`: Exclude original Java files.
- `--no-classes`: Exclude class files (`*.cjava`).
- `--no-methods`: Exclude method files (`*.mjava`).
- `--no-fields`: Exclude field files (`*.fjava`).
- `--comments`: Include comment files (`*.?java.com`).
- `--separate-comments`: Exclude comments from module files.
- `--class-ext=<ext>`: Class file extension. _Default: .cjava_.
- `--method-ext=<ext>`: Method file extension. _Default: .mjava_.
- `--field-ext=<ext>`: Field file extension. _Default: .fjava_.
- `--comment-ext=<ext>`: Comment file extension. _Default: .com_.
- `--digest-params`: Digest parameters.
- `--unqualify`: Unqualify typenames.
- `--parsable`: Generate more parsable files. Specifically, this option adds a package name declaration and a class declaration for method files.

#### @tokenize
Splits lines in input files so that each line contains mostly one token using a simple regular expression.
More specifically, it rewrites all the line breaks into "\r" and inserts "\n" into all the token boundaries.

#### @tokenize-jdt
Splits lines in Java source files (_LineToken_ format) so that each line contains at most one Java token using [Eclipse-JDT](https://projects.eclipse.org/projects/eclipse.jdt).
More specifically, it rewrites all the line breaks into "\r" and inserts "\n" into all the token boundaries.

#### @untokenize
Decodes _LineToken_ files into the original one.

#### @convert
A general-purpose blob converter via external runnables or HTTP Web API.

Options:
- `--cmd=<cmdline>`: Command with arguments. The blob is written to a temporary file and the command is executed.
- `--endpoint=<url>`: HTTP Web API endpoint. The blob is POSTed and the response body is used as the result.
- `--filter`: Filter mode (with `--cmd`). The blob is piped to stdin and stdout is captured.
- `--no-shell`: Do not wrap the command with `/bin/sh -c`.

Options to limit the target:
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive matching for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @filter
A blob filter by filename and/or file size.
Options:
- `--pattern=<glob>`: Specify the target files as a wildcard glob; remove non-matched files.
- `-i`, `--ignore-case`: Perform case-insensitive matching for the given pattern.
- `--size=<num>{,K,M,G}`: The blob size threshold; remove files larger than this size.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @cregit
Converts source files to [cregit](https://github.com/dmgerman/tokenizers) format via [srcML](https://www.srcml.org/).
Options:
- `--srcml=<cmd>`: Location of executable `srcml` command. _Default: srcml_.
- `-l`, `--lang=<language>`: Target language (`C`, `C++`, `C#`, `Java`).

Options to limit the target:
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive matching for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

### Commit Translators
_Commit translators_ provide a commit-to-commit(s) translations.

#### @note-commit
Prepends the original commit ID to each commit message.
When applied after another transformation, the original (pre-transformation) commit ID is retrieved from Git notes.
Options:
- `--length=<num>`: Length of SHA1 hash. _Default: 40_.

#### @svn-metadata
Attaches svn commit IDs into a Git repository generated by [svn2git](https://github.com/svn-all-fast-export/svn2git).
Options:
- `--svn-mapping=<log-git-repository>`: Specify the svn mapping file.
- `--object-mapping=<marks-git-repository>`: Specify the object mapping file.


### Others

#### @cluster
Restructures commit graph.
Options:
- `--recipe=<file>`: Specify a _recipe_ JSON file that describe how the commit graph should be restructured.
- `--dump-graph=<file>`: Dump the restructured graph in GML format.

#### @extract-commit
Extracts a specific change (a specific commit and its first-parent chain).
Options:
- `--target=<commit>`: Specify the target commit to extract.

#### @anonymize
Anonymizes filenames, blob content, commit messages, branch/tag names, and author/committer identities.
Options:
- `--all`: Enable all anonymization options.
- `--tree`: Anonymize directory names.
- `--link`: Anonymize link names.
- `--blob`: Anonymize file names.
- `--content`: Anonymize file contents.
- `--message`: Anonymize commit/tag messages.
- `--branch`: Anonymize branch names (except `main`/`master`).
- `--tag`: Anonymize tag names.
- `--author`: Anonymize author/committer names.
- `--email`: Anonymize author/committer emails.

#### @external
Run an external rewriter class.
Options:
- `--class=<class>`: Fully qualified class name of the rewriter.
- `--args=<args>`: Arguments to pass to the rewriter.

#### @id
A no-op rewriter that copies all objects without transformation.
Useful for verifying that the rewriting pipeline preserves repository content.


## Parallel Rewriting

With `-j`, git-stein rewrites trees in parallel using multiple threads.
The rewriting is done in two passes over the commit history:

1. **Tree rewriting pass** (parallel): all root trees are rewritten in parallel using a `ForkJoinPool`.
The commit list is split into contiguous chunks, and each chunk is processed by a worker thread.
Consecutive commits within a chunk share many tree entries, so the entry mapping cache is effective within each chunk.
2. **Commit writing pass** (sequential): commits are written in topological order.
Since each commit depends on its parent's ID, this pass must be sequential.
The tree rewriting results are looked up from the first pass.

The number of threads can be specified explicitly (e.g., `-j4`) or left to default (`-j` alone uses all available processors).


## Chaining Commands

Multiple commands can be listed on a single command line.
They are applied sequentially as separate transformation steps.
For example, with three commands `@A @B @C`:
```
source → target/.git/.git-stein.1 → target/.git/.git-stein.2 → target
         (@A)                        (@B)                        (@C)
```
Intermediate repositories (`.git-stein.N`) are bare repositories created under the target's `.git` directory.

As an optimization, consecutive blob translators are composed into a single pass rather than creating intermediate repositories for each one.
This behavior can be disabled with `--no-composite`.
For example, the following runs `@historage-jdt` and `@cregit` as a single composed blob translator, then `@note-commit` as a separate commit translator step:
```
$ git stein path/to/repo -o path/to/out \
  @historage-jdt --no-original --no-classes \
  @cregit --pattern='*.cjava' --ignore-case \
  @note-commit
```


## Notes

git-stein records the original commit ID as a git note on each target commit (enabled by default).
Each note stores the source commit ID as a 40-character hex string.
This provides the standard way to trace a target commit back to its source, and is visible in `git log` without any extra options (via `refs/notes/commits`).
Notes are also used for [Incremental Transformation](#incremental-transformation) to skip already-processed commits on subsequent runs.

`@note-commit` reads the note on each commit and embeds the original commit ID into the commit message.
Place it at the end of the command list:
```
$ git stein path/to/repo -o path/to/out @historage-jdt @note-commit
```

git-stein uses three notes refs:
`refs/notes/git-stein-prev` stores the immediate source commit ID (i.e., the commit in the input repository of this transformation step),
`refs/notes/git-stein-orig` stores the original source commit ID (traces back through chained transformations to the very first source),
and `refs/notes/commits` points to the same object as `git-stein-orig` (visible in `git log` by default).
For a single transformation, all three refs point to the same notes object.
In a chained transformation (see [Chaining Commands](#chaining-commands)), `git-stein-prev` and `git-stein-orig` may differ.
For example, in `.git-stein.2`, `git-stein-prev` points to the commit in `.git-stein.1`, while `git-stein-orig` points to the commit in the original source.

If `--no-notes` is used, no notes are written, and incremental transformation will not be available on subsequent runs.
The target will be fully rewritten each time.


## Incremental Transformation

git-stein supports incremental transformation:
when the target repository already contains results from a previous run, only new commits are processed.

On subsequent runs, git-stein reads the notes from the target repository to reconstruct the commit mapping and skips already-processed commits.

New commits still need to be transformed.
To try to speed up the transformation of these new commits by reusing previously computed entry mappings, try `--cache` (see [Persistent cache](#persistent-cache-cache)).


## Caching

git-stein uses two levels of caching to avoid redundant work:
an in-memory cache for the current run and an optional persistent cache for repeated runs.

### In-memory cache

During a single run, git-stein keeps an in-memory entry mapping (source entry → transformed entry) backed by a Guava Cache with LRU eviction.
This avoids re-transforming identical entries within the same execution.
The memory budget is controlled by `--mapping-mem` (default: 25% of max heap).

### Persistent cache (`--cache`)

When `--cache` is enabled, the entry mapping is stored in an MVStore (H2) file (`cache.mv.db`) in the target repository's `.git` directory.
This persists entry mappings across runs, so entries that were already transformed in a previous run can be reused without re-computation.
The `--mapping-mem` option also controls the MVStore page cache and write buffer sizes.

`--cache` and the in-memory cache are mutually exclusive:
when `--cache` is enabled, MVStore replaces the in-memory Guava Cache entirely.


## Publications
The following article includes the details of the incremental transformation (and a brief introduction to git-stein).
Those who have used git-stein in their academic work may be encouraged to cite the following in their work:

Shunta Shiba, Shinpei Hayashi: ``Historinc: A Repository Transformation Tool for Fine-Grained History Tracking'' (in Japanese), Computer Software, Vol. 39, No. 4, pp. 75-85, 2022. https://doi.org/10.11309/jssst.39.4_75
```
@article{shiba-jssst202211,
    author = {Shunta Shiba and Shinpei Hayashi},
    title = {{Historinc}: A Repository Transformation Tool for Fine-Grained History Tracking (in Japanese)},
    journal = {Computer Software},
    volume = 39,
    number = 4,
    pages = {75−−85},
    year = 2022,
    doi = {10.11309/jssst.39.4_75}
}
```
