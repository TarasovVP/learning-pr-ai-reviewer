# Security policy

Please report suspected vulnerabilities privately through GitHub's security advisory feature rather than opening a public issue.

This action handles untrusted Pull Request content. Integrators should pin the action to a reviewed full commit SHA, keep provider credentials in the parent repository or organization, grant only the documented workflow permissions, and avoid adding steps that execute submitted code in the privileged `pull_request_target` job.

The automated report is advisory and must not be used as the sole authorization mechanism for merging or deploying untrusted changes.
