---
name: release-local-apk
description: Cut a PriceWatch release on this machine and put the signed APK in the shared folder (the from_claude share, mounted at /mnt/from-claude). Bumps versionCode/versionName through a release PR, tags vX.Y, builds the release APK with the local signing key, checks its version and signature, and copies it to the share. Use this whenever the user asks to cut, make or ship a release, tag a version, build "the next APK" or a new APK for their phone, or upload an APK to the shared folder, even if they don't say "release".
---

# Release a local APK

PriceWatch is sideloaded, not published. A release is a `vX.Y` tag on `main` plus a signed APK in
the shared folder, from where the user installs it on their phone.

Run everything from the repository root. `scripts/release_apk.sh` (next to this file) does the
mechanical parts and fails with a plain message when something is off; it also sets up this
machine's JDK and Android SDK, which are not on PATH. Below, `release` stands for
`.claude/skills/release-local-apk/scripts/release_apk.sh`.

## 1. Check that a release can be made

    release check

This changes nothing. It confirms you are on `main`, with a clean tree that matches `origin/main`.
It also confirms the signing key is present (`signing/keystore.properties`), the JDK and build
tools are found, `gh` is logged in, and something writable is mounted at the shared folder. If the
user names another folder, pass it as a second argument here and to `upload`.

If a check fails, stop and tell the user what to fix before anything gets tagged. The mount check
matters even though the copy comes last. When the share is not mounted, `/mnt/from-claude` is an
ordinary empty folder on the local disk, so a copy "succeeds" but never reaches the share. If the
share is the only problem and the user wants the release anyway, carry on and run `upload` once
it is mounted.

## 2. Pick the version

Read `versionCode` and `versionName` in `app/build.gradle.kts`. Use the version the user asks for;
otherwise bump the minor version (1.1 → 1.2) and say which you chose. Always raise `versionCode` by
one, because Android refuses an update whose versionCode is lower than the installed app's. Make
sure the tag `v<version>` does not exist yet (`git tag -l`).

## 3. Test what is on main

    release test

The release is built from `main`, and two PRs can each pass on their own and still break each other
once both are merged. On a failure, stop and report it; don't release around it.

## 4. Bump the version through a PR, then tag the merge

Changes reach `main` through PRs, and the version bump follows the same route:

    git switch -c release-<version>
    # set versionCode and versionName in app/build.gradle.kts
    git commit -am "Release <version>"
    git push -u origin release-<version>
    gh pr create --base main --title "Release <version>" --body "<notes>"
    gh pr merge --merge --delete-branch
    git switch main && git pull --ff-only
    git tag -a v<version> -m "PriceWatch <version>" -m "<notes>"
    git push origin v<version>

Before running these, collect the notes, which list what the release contains. Each PR merge commit
carries the PR's title as its body, so
`git log --merges --format='- %b' <previous tag>..HEAD` lists them (use all of history for the
first tag).

Never move or re-push an existing tag: tags are public and may already be fetched. If something is
wrong after tagging, fix it and release the next version.

## 5. Build and verify

    release build

This builds `assembleRelease` from the tagged commit, which takes a few minutes on this machine.
Then it checks the APK: package `app.pricewatch`, the versionCode and versionName from the build
file, and a signature by the key in `signing/keystore.properties`, not a debug key or the old
public key.

## 6. Upload

    release upload

This copies the APK to `/mnt/from-claude/pricewatch-<version>.apk`, writes a `.sha256` file next to
it, and checks the copy. It never replaces an existing file. Later (on the tag, after `build`) it
can be run again by itself, for instance once the share is mounted.

## 7. Report

Tell the user:
- the version and versionCode
- the tag and the release PR
- what is in the release (the notes)
- the APK's path in the share and its SHA-256
- the signing certificate's SHA-256

If their phone still has a build signed with an older key, remind them to uninstall that app once
before installing, which deletes its watchlist.

## Keep secrets out

Never print, log or commit `signing/keystore.properties` or the key file. The script reads the
password only through a pipe to `keytool`, so you never need to open that file yourself.
