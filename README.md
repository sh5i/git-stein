# git-stein
[![jitpack](https://jitpack.io/v/sh5i/git-stein.svg)](https://jitpack.io/#sh5i/git-stein)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/sh5i/git-stein/blob/master/LICENSE)

_git-stein_ is a Java framework for rewriting Git repositories.
Users can use this framework to implement their desired history rewriting by customizing the default behavior of the framework.
Several [bundle applications](#bundle-apps) using this framework are also available; they are not only practical but also helpful in understanding how to use this framework.


## Build and Run
Build:
```
$ git clone https://github.com/sh5i/git-stein.git
$ cd git-stein
$ ./gradlew executableJar
$ cp build/libs/git-stein.jar /path/to/git/exec/path/git-stein  # Add as a Git subcommand
```
You can know the git subcommand path (`/path/to/git/exec/path` in this example) by `git --exec-path`.

Run command:
```
$ java -jar build/libs/git-stein.jar [options...]
$ build/libs/git-stein.jar [options...]   # In UNIX-like system
$ git stein [options...]                  # When subcommand available
```

## Recipes

Converts a Java repository to a method one, and tokenize each method file in it
```
$ git stein path/to/source-repo -o path/to/target-repo \
  @historage-jdt --no-original --no-classes \
  @cregit --pattern='*.cjava' --ignore-case
```

Applies a user script to all rust source code
```
$ git stein path/to/source-repo -o path/to/target-repo \
  @convert-cmd --cmd=/usr/bin/xxx --pattern='*.rs' --ignore-case
```


## General Options
- `-o`, `--output=<path>`: Specify the destination repository path. If it is omitted, git-stein runs as _overwrite_ mode (rewriting the input repo).
- `-d`, `--duplicate`: Duplicate the source repository and overwrites it. **Requires `-o`**.
- `--clean`: Delete the target repository before applying the transformation if it exists. **Requires `-o`**.
- `--bare`: Treat that the specified repositories are bare.
- `-j`, `--jobs=<nthreads>`: Rewrites trees in parallel using `<nthreads>` threads. If the number of threads is omitted (just `-j` is given), _total number of processors - 1_ is used.
- `-n`, `--dry-run`: Do not actually modify the target repository.
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
- `-i`, `--ignore-case`: Perform case-insensitive mathcing for the given pattern.
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
- `--parsable`: Generate more parsable files. Specifically, this option adds a package name declration and a class declaration for method files.

#### @tokenize
Splits lines in input files so that each line contains mostly one token using a simple regular expression.
More specifically, it rewrites all the line breaks into "\r" and inserts "\n" into all the token boundaries.

#### @tokenize-jdt
Splits lines in Java source files (_LineToken_ format) so that each line contains at most one Java token using [Eclipse-JDT](https://projects.eclipse.org/projects/eclipse.jdt).
More specifically, it rewrites all the line breaks into "\r" and inserts "\n" into all the token boundaries.

#### @untokenize
Decodes _LineToken_ files into the original one.

#### @convert-http
A general-purpose blob converter via an HTTP Web API.
Options:
- `--endpoint=<url>`: Specify the endpoint URL of the HTTP Web API.
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive mathcing for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @convert-cmd
A general-purpose blob converter via an executable command.
Options:
- `--cmd=<cmdline>`: Command with arguments.
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive mathcing for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @grep
A blob filter by filename.
Options:
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive mathcing for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @size-filter
A blob filter by file size.
Options:
- `--size=<num>{,K,M,G}`: The blob size threshold; remove files larger than this size.
- `-V`, `--invert-match`: Select non-matching items for targets.

#### @cregit
cregit format via [srcML](https://www.srcml.org/).
Options:
- `--srcml=<cmd>`: Location of executable `srcml` command. _Default: srcml_.
- `--pattern=<glob>`: Specify the target files as a wildcard glob.
- `-i`, `--ignore-case`: Perform case-insensitive mathcing for the given pattern.
- `-V`, `--invert-match`: Select non-matching items for targets.

### Commit Translators

#### @note-commit
Note original commit ID on each commit message.
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

#### @anonymize
Anonymizes filenames, branch names, tag names, and file contents.

#### @external
Run an external rewriter.

#### @identity
A test app to do nothing.


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
