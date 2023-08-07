# git-stein
[![jitpack](https://jitpack.io/v/sh5i/git-stein.svg)](https://jitpack.io/#sh5i/git-stein)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/sh5i/git-stein/blob/master/LICENSE)

_git-stein_ is a Java framework for rewriting Git repositories.
Users can use this framework to implement their desired history rewriting by customizing the default behavior of the framework.
Several [bundle applications](#bundle-apps) using this framework are also available; they are not only practical but also helpful in understanding how to use this framework.


## Build and Run
```
$ git clone https://github.com/sh5i/git-stein.git
$ cd git-stein
$ ./gradlew shadowJar
```

```
$ java -jar build/libs/git-stein-all.jar <app> [options...]
# Converts a Java repository to a method repository
$ java -jar build/libs/git-stein-all.jar Historage path/to/source -o path/to/target
```

## General Options
- `-o`, `--output=<path>`: Specify the destination repository path. If it is omitted, git-stein runs as _overwrite_ mode.
- `-d`, `--duplicate`: Duplicate the source repository and overwrites it. **Requires `-o`**.
- `--clean`: Delete the destination repository before applying the transformation if it exists. **Requires `-o`**.
- `--bare`: Treat that the specified repositories are bare.
- `-p`, `--parallel=<nthreads>`: Rewrites trees in parallel using `<nthreads>` threads. If the number of threads is omitted (just `-p` is given), _total number of processors - 1_ is used.
- `-n`, `--dry-run`: Do not actually modify the target repository.
- `--[no-]notes-forward`: Note the object ID of rewritten commits to the commits in the source repository. _Default: no_.
- `--[no-]notes-backward`: Note the object ID of original commits to the commits in the destination repository. _Default: yes_.
- `--extra-attributes`: Allow opportunity to rewrite the encoding and the signature fields in commits.
- `--mapping=<file>` Store the commit mapping to `<file>` as JSON format.
- `--cache=<level>,...`: Specify the object types for caching (`commit`, `blob`, `tree`. See [Incremental transformation](#incremental-transformation) for the details). Default: none. `commit` is recommended.
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
With the option `-cache=<level>`, an SQLite3  cache file "cache.db" will be stored in the `.git` directory of the destination repository.
This file records the correspondence between objects before and after transformation, according to the specified option.
Correspondences between commits (`—cache=commit`), between trees (`—cache=tree`), and between files (`—cache=blob`) are stored.
This cache can save the re-transformation of remaining objects during the second and subsequent transformation trials.


## Bundle Apps

### Historage
Generates a [Historage](https://github.com/hideakihata/git2historage)-like repository from a Java-based project repository.

Options:
- `--[no-]classes`: Include class files (`*.cjava`). _Default: yes_.
- `--[no-]fields`: Include field files (`*.fjava`). _Default: yes_.
- `--[no-]methods`: Include method files (`*.mjava`). _Default: yes_.
- `--[no-]original`: Include original Java files. _Default: yes_.
- `--[no-]noncode`: Include non-Java files. _Default: yes_.
- `--comments`: Include comment files (`*.?java.com`).
- `--separate-comments`: Exclude comments from module files.

### LineTokenizer
Splits lines in Java source files (_LineToken_ format) so that each line contains at most one Java token.
More specifically, it rewrites all the line breaks into "\r" and inserts "\n" into all the token boundaries.

Options:
- `--decode`: Decode LineToken files into the original one instead of converting to LineToken files.

### Anonymizer
Anonymizes filenames, branch names, tag names, and file contents.

### Clusterer
Restructures commit graph.

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
Attaches svn commit IDs into a Git repository generated by [svn2git](https://github.com/svn-all-fast-export/svn2git).

Options:
- `--svn-mapping=<log-git-repository>`: Specify the svn mapping file.
- `--object-mapping=<marks-git-repository>`: Specify the object mapping file.
                             
### Identity
A test app to do nothing.


## Publications
The following article includes the details of the incremental transformation (and a brief introduction to git-stein). Those who have used git-stein in their academic work may be encouraged to cite the following in their work:
```
@article{shiba-jssst202211,
    author = {Shunta Shiba and Shinpei Hayashi},
    title = {{Historinc}: A Repository Transformation Tool for Fine-Grained History Tracking (in Japanese)},
    journal = {Computer Software},
    volume = 39,
    number = 4,
    pages = {75−−85},
    year = 2022,
}
```
