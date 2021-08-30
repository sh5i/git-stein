# git-stein
A general-purpose Git repository rewriter

[![jitpack](https://jitpack.io/v/sh5i/git-stein.svg)](https://jitpack.io/#sh5i/git-stein)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/sh5i/git-stein/blob/master/LICENSE)

## Build
```
$ git clone https://github.com/sh5i/git-stein.git
$ cd git-stein
$ cp gradle.properties-sample gradle.properties  # if you will run builtin apps
$ ./gradlew shadowJar
$ java -jar build/libs/git-stein-all.jar <app> [options...]
```

## Rewriting mode
git-stein supports three rewriting modes.
- _overwrite_ mode (`<source>`): given a source repository, rewriting it.
- _transform_ mode (`<source> -o <target>`): given source and target repositories, rewriting objects in the source repository and storing them in the target repository.
- _duplicate_ mode (`<source> -o <target> -d`): given a source repository and a path for the target repository, copying the source repository into the given path and applying overwrite mode to the target repository.

## General Options
- `-o`, `--output=<path>`: Specify the destination repository. If it is omitted, git-stein runs as _overwrite_ mode.
- `-d`, `--duplicate`: Duplicate the source repository and overwrites it. **Requires `-o`**.
- `--clean`: Delete the destination repository before applyring the transformation if it exists. **Requires `-o`**.
- `--bare`: Treat that the specified repositories are bare.
- `-p`, `--parallel=<nthreads>`: Rewrites trees in parallel using `<nthreads>` threads. If the number of threads is omitted (just `-p` is given), _total number of processors - 1_ is used.
- `-n`, `--dry-run`: Do not actually modify the target repository.
- `--[no-]notes-forward`: Note the object ID of rewritten commits to the commits in the source repository. _Default: no_.
- `--[no-]notes-backward`: Note the object ID of original commits to the commits in the destination repository. _Default: yes_.
- `--extra-attributes`: Allow opportunity to rewrite the encoding and the signature fields in commits.
- `--mapping=<file>` Store the commit mapping to `<file>` as JSON format.
- `--cache-level=<object-type>`: Object type for saving cache. `Commit` is recommended.
- `--log=<level>`: Specify log level (default: `INFO`).
- `-q`, `--quiet`: Quiet mode (same as `--log=ERROR`).
- `-v`, `--verbose`: Verbose mode (same as `--log=DEBUG`).
- `--help`: Show a help message and exit.
- `--version`: Print version information and exit.

## Builtin Apps

### Historage

Generate a [Historage](https://github.com/hideakihata/git2historage)-like repository from a Java-based project.

Options:
- `--[no-]classes`: Include class files (`*.cjava`). _Default: yes_.
- `--[no-]fields`: Include field files (`*.fjava`). _Default: yes_.
- `--[no-]methods`: Include method files (`*.mjava`). _Default: yes_.
- `--[no-]original`: Include original Java files. _Default: yes_.
- `--[no-]noncode`: Include non-Java files. _Default: yes_.
- `--comments`: Include comment files (`*.?javacom`).
- `--separate-comments`: Exclude comments from module files.

### LineTokenizer

Split lines in Java source files (_LineToken_ format) so that each line contains at most one Java token.

Options:
- `--decode`: Decode LineToken files into the original one instead of converting to LineToken files.

### Anonymizer

Anonymize filenames, branch and tag names, and file contents.

### Clusterer

Restructure commit graph.

Options:
- `--recipe=<file>`: Specify a _recipe_ JSON file that describe how the commit graph should be restructured.
- `--dump-graph=<file>`: Dump the restructured graph in GML format.
      
### Converter

A general-purpose blob converter via an HTTP Web API.

Options:
- `--endpoint=<url>`: Specify the endpoint URL of the HTTP Web API.
- `--pattern=<glob>`: Specify the target files.
- `--exclude`: Exclude non-target files.

### SvnMetadataAnnotator

Attach svn commit IDs into a Git repository generated by svn2git.

Options:
- `--svn-mapping=<log-git-repository>`: Specify the svn mapping file.
- `--object-mapping=<marks-git-repository>`: Specify the object mapping file.
                             
### Identity

A test app to do nothing.
