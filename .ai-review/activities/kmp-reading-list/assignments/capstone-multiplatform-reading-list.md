# Capstone. Multiplatform Reading List

Create a Kotlin Multiplatform application for managing a small reading list. The same repository should provide applications for Android, iOS, Desktop, and Web.

The application must let a user browse books from a public HTTP API, open basic book details, save books to a personal reading list, and view the saved list without a network connection. Show clear loading, empty, content, and recoverable error states.

Organize reusable domain and data behavior in shared Kotlin source sets. Keep target-specific code limited to platform integration, storage drivers, HTTP engines, and user-interface entry points where those differences are necessary. The shared data layer must expose a clear repository boundary, map remote and stored representations into domain models, and define an explicit cache/fallback policy.

Use Ktor Client for HTTP communication and SQLDelight for local persistence. Configure dependencies without global service locators; constructor-based dependency injection or a multiplatform DI framework are both acceptable. Compose Multiplatform may be used for shared presentation, but separate native interfaces are equally valid when the repository explains the boundary.

Add focused unit tests for shared domain and data behavior. Include success, remote failure with stored data, empty results, and persistence of reading-list changes. Keep credentials and machine-specific configuration outside version control.

Submit the completed work through a Pull Request titled exactly `Capstone. Multiplatform Reading List`. In the description, summarize the source-set structure, shared/platform boundary, data and caching decisions, UI approach, supported targets, verification performed, and known limitations.
