# Publishing ringkit to pub.dev

This is an operator checklist for a deliberate public release. It does **not**
authorize an automated publish, authenticate a CI job, or reserve a package
name.

The website is **[pub.dev](https://pub.dev)**. `flutter pub get` downloads
dependencies; `flutter pub publish` uploads your package.

## Before a release

1. Confirm the intended package name is still available. A `404` from
   `https://pub.dev/api/packages/ringkit` means no package exists at that
   instant; it does not reserve the name.
2. Choose the exact version in `pubspec.yaml`, update `CHANGELOG.md`, and
   verify the release uses the intended branch and commit.
3. Confirm `README.md`, `LICENSE`, repository links, issue tracker, topics,
   and public API documentation are accurate. Keep the telemetry feature
   default-off unless callers explicitly configure it, and disclose its
   behavior in public documentation.
4. Confirm every file in the package archive is safe to redistribute. In
   particular, do not ship API keys, tokens, service-account files, Firebase
   configuration, device logs, private telemetry data, or internal planning
   material. `.pubignore` replaces `.gitignore` for pub archive selection, so
   update its safety rules when either ignore policy changes.
5. Run the project verification required for the release, including the
   relevant Flutter, Dart, Android, and device gates. A pub dry run validates
   only package layout and archive content; it is not a device-behavior test.

## Validate the archive

From the package root, with the release Flutter SDK:

```sh
flutter pub publish --dry-run
```

Resolve every validation error and review the complete file list that the
command prints. Do not use `--force` or `--skip-validation` to bypass this
review. Repeat the dry run after each archive or metadata change.

## Publish intentionally

Publication requires the owner-controlled Google Account that will become the
initial uploader. Run this command only when the release owner is present and
has reviewed the final dry-run output:

```sh
flutter pub publish
```

The command checks the package, displays the archive, and uploads only after
interactive confirmation. Do not paste credentials, tokens, or service-account
files into the repository, terminal transcript, screenshots, or release notes.

Publishing creates a durable public version: users can depend on it, and
unpublishing is exceptional. A recent version can sometimes be retracted, but
retraction is not deletion. Treat the version, archive, and ownership choice as
irreversible release decisions.

## Optional verified publisher

A verified publisher is recommended for an organization with a domain it can
verify through Google Search Console. However, pub.dev does not support
publishing a **new** package directly to a verified publisher: publish the
first version with the chosen Google Account, then transfer it from the
package Admin page. The transfer cannot be reversed, so ensure the uploader is
also an administrator of the destination publisher before starting.

After the initial release, add appropriate backup uploaders or publisher
members, and consider automated publishing only after the manual release path
is proven.

## References

After publication, verify the package page and test installation in a fresh
Android Flutter app using `flutter pub add ringkit`. Update the README's
Git-only installation notice and release notes only after the version is
actually visible on pub.dev.

- [Dart: Publishing packages](https://dart.dev/tools/pub/publishing)
- [Dart: `dart pub publish`](https://dart.dev/tools/pub/cmd/pub-lish)
- [Dart: Verified publishers](https://dart.dev/tools/pub/verified-publishers)
