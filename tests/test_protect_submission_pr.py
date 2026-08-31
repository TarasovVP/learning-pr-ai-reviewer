from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from github_api import GitHubApiError
from protect_submission_pr import protect_submission


class FakeApi:
    def __init__(self, missing=False):
        self.calls = []
        self.missing = missing
    def patch(self, path, payload):
        self.calls.append(("PATCH", path, payload))
    def delete(self, path):
        self.calls.append(("DELETE", path, None))
        if self.missing:
            raise GitHubApiError(404, path, "missing")


class ProtectSubmissionTest(unittest.TestCase):
    def test_closes_before_removing_legacy_label(self):
        api = FakeApi()
        protect_submission(api, "owner/repo", 3)
        self.assertEqual("PATCH", api.calls[0][0])
        self.assertEqual({"state": "closed"}, api.calls[0][2])
        self.assertEqual("DELETE", api.calls[1][0])

    def test_missing_legacy_label_does_not_block_protection(self):
        api = FakeApi(missing=True)
        protect_submission(api, "owner/repo", 3)
        self.assertEqual(2, len(api.calls))


if __name__ == "__main__":
    unittest.main()
