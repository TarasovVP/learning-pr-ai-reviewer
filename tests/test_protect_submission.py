from pathlib import Path
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from protect_submission import protect


class FakeApi:
    def __init__(self, permission="read"):
        self.permission = permission
        self.requests = []

    def get(self, path):
        if "/collaborators/" in path:
            return {"permission": self.permission}
        return {"user": {"login": "student"}}

    def request(self, method, path, payload):
        self.requests.append((method, path, payload))


class ProtectSubmissionTest(unittest.TestCase):
    def test_automated_review_closes_submission(self) -> None:
        api = FakeApi()
        protect(api, "owner/repo", 1, "opened", "")
        self.assertEqual("PATCH", api.requests[0][0])

    def test_author_can_request_manual_review(self) -> None:
        api = FakeApi()
        protect(api, "owner/repo", 1, "manual", "student")
        self.assertEqual(1, len(api.requests))

    def test_unauthorized_manual_request_does_not_close(self) -> None:
        api = FakeApi("read")
        with self.assertRaisesRegex(ValueError, "not the author"):
            protect(api, "owner/repo", 1, "manual", "outsider")
        self.assertEqual([], api.requests)

    def test_maintainer_can_request_manual_review(self) -> None:
        api = FakeApi("maintain")
        protect(api, "owner/repo", 1, "manual", "mentor")
        self.assertEqual(1, len(api.requests))


if __name__ == "__main__":
    unittest.main()
