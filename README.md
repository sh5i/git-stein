# git-stein
[![Java CI](https://github.com/sh5i/git-stein/actions/workflows/main.yml/badge.svg)](https://github.com/sh5i/git-stein/actions/workflows/main.yml)
[![jitpack](https://jitpack.io/v/sh5i/git-stein.svg)](https://jitpack.io/#sh5i/git-stein)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/sh5i/git-stein/blob/main/LICENSE)

_git-stein_ is a general-purpose Java framework for rewriting Git repositories.
Users can use this framework to implement their desired history rewriting by customizing the default behavior of the framework.
Several [bundle applications](#bundle-apps) using this framework are also available; they are not only practical but also helpful in understanding how to use this framework.

## Requirements

- **Running**: Java 11 or later
- **Building**: Java 17 or later (required by Gradle 9)

> **Note**: This is the last release supporting Java 11 as a runtime target.
> Future versions will require Java 17 or later.


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

### Chaining commands

Multiple commands can be listed on the command line.
They are applied sequentially; intermediate repositories are created under `.git/.git-stein.N` in the target directory and cleaned up automatically.
As an optimization, consecutive blob translators are composed into a single pass.

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

### Tracking original commit IDs

When git-stein rewrites a repository, it records the original commit ID in Git notes (enabled by default).
`@note-commit` reads these notes and prepends the original commit ID to each commit message.

A typical workflow is to first transform, then apply `@note-commit`:
```
$ git stein path/to/repo -o path/to/out @historage-jdt @note-commit
```
After this, each commit message in `step2` starts with the original commit ID from `repo`.
This works even after multiple transformations — the notes trace back to the original.

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
- `-j`, `--jobs=<nthreads>`: Rewrites trees in parallel using `<nthreads>` threads. If the number of threads is omitted (just `-j` is given), _total number of processors - 1_ is used.
- `-n`, `--dry-run`: Do not actually modify the target repository.
- `--stream-size-limit=<num>{,K,M,G}`: increase the stream size limit.
- `--no-notes`: Stop noting the source commit ID to the commits in the target repository.
- `--no-pack`: Stop packing objects after transformation finished.
- `--no-composite`: Stop composing multiple blob translators.
- `--extra-attributes`: Allow opportunity to rewrite the encoding and the signature fields in commits.
- `--cache=<level>,...`: Specify the object types for caching (`commit`, `blob`, `tree`. See [Incremental transformation](#incremental-transformation) for the details). Default: none. `commit` is recommended.
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


## Incremental Transformation
In case the source repository to be transformed has been evolving, git-stein can transform only newly added objects.
With the option `--cache=<level>`, an SQLite3  cache file "cache.db" will be stored in the `.git` directory of the destination repository.
This file records the correspondence between objects before and after transformation, according to the specified option.
Correspondences between commits (`--cache=commit`), between trees (`--cache=tree`), and between files (`--cache=blob`) are stored.
This cache can save the re-transformation of remaining objects during the second and subsequent transformation trials.


## Bundle Apps

### Blob Translators
_Blob translators_ provide a blob-to-blob(s) translations.
Multiple blob translators can be composed and applied in a single pass.

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
