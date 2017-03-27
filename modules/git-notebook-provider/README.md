# Git notebook provider

Support for git backed notebook provider.

## Settings

- `local_path`: where the git repo is located on the local drive; no default - **required**
- `remote`: optional, remote address, if specified, the remote will be cloned when the provider is initialized; if not given, a repo will be created at the `local_path`
- `branch`: optional; default `master`
- `authentication.key_file`: path to the key file; only applies to SSH
- `authentication.key_file_passphrase`: passphrase for the key file; not required; no default
- `authentication.username`: repository username; HTTP(S) only
- `authentication.password`: repository password

## Remotes

The provider supports HTTP, HTTPS and SSH protocols. The remote URI needs to be in the following format, for SSH:

    ssh://user@example.com/repo.git

  P.S. For gitub use this: `ssh://git@github.com/some-github-organization/some-repo.git`


For HTTP / HTTPS:

    http(s)://example.com/repo.git

## Authentication

The provider first checks for `key_file`, if none specified, it will look for the `username` and `password`. If none given, the provider assumes no authentication.
If `key_file` is specified but the file does not exist, the provider will fail to initialize.
If the `key_file` is protected with the passphrase, specify it with `key_file_passphrase` property.
If using HTTP(S), specify both, `username` and `password` in the `authentication` section.
If using `password` based SSH authentication, specify `authentication.password` only.

## Pushing to remote

If a `remote` is specified, all changes in the local repository will be pushed with `-f` (force) to the remote.

## Using with the spark-notebook(-enterpise)

In the configuration of the spark-notebook, set the following properties:

    manager {
      notebooks.io.provider = "notebook.io.GitNotebookProvider"
      ...
    }

    notebook.io.GitNotebookProvider {
      // optional: remote = ""
      // optional: branch = ""
      local_path = ${manager.notebooks.dir}
      authentication {
        key_file = "${MESOS_SANDBOX}/git.key"
      }
    }

## Unit tests

The HTTPS and SSH intergration tests are by ignored by default (as GitHub credentials are needed).

To run those tests, open the `GitNotebookProviderWithCloneTests.scala` and remove the `@Ignore` tags.
Next, see `./test-git-support.bash` script, and add make the corresponding environment variables available (you may store them to `~/.bashrc` too).

For example:
 - first generate a GitHub token (go to https://github.com/settings/tokens , click on `Generate new token` button, select `repo` and `user` scopes)
 - run the `./test-git-support.bash`
 - if all tests pass, you should see two new commits matching the current unit test
