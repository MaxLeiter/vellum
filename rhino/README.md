# Rhino

Vellum runs page scripts in [Mozilla Rhino](https://github.com/mozilla/rhino) (MPL-2.0), built from source:
the upstream commit pinned in `gradle.properties` (`rhino_commit`, the 1.9.1 release) with the patches in
`patches/` applied in order. The classes are relocated to `dev.vellum.shadow.rhino`, so they never clash with
another mod's copy of Rhino.

`build.gradle` downloads the GitHub archive of the pinned commit once into the Gradle user home
(`caches/vellum-rhino/`), checks it against `rhino_sha256`, applies the patches to the core module
(`rhino/src/main`) and compiles it. It applies them again only when a patch changes. A patch that does not apply
fails the build with its name. This needs `git` on the path.

## The patches

| Patch | What it does |
|---|---|
| `0001` | `let` and `const` get a fresh binding in each loop iteration; `const` is block scoped from ES6 on; `for (const ...)` heads |
| `0002` | Spread arguments in calls and `new`: `f(...a, b)`, `new C(...a)` |
| `0003` | `class`: methods, accessors, statics, `extends`, `super`, fields, static blocks, `new.target` |
| `0004` | `async` functions, arrows and methods, and `await` |
| `0005` | A size limit on BigInt arithmetic and parsing, which Vellum sets from `Limits.maxBigIntBits` |

The patches hold only Rhino's core module (`rhino/src/main`), the part Vellum builds. Vellum's own tests
(`LanguageConformanceTest`, `NewSyntaxSandboxTest`) cover them through the sandbox.

## Changing the patches

```bash
./gradlew :rhino:applyRhinoPatches    # rhino/work: upstream as one commit tagged "upstream", then one commit per patch
```

`rhino/work` is an ordinary git repository (ignored by Vellum's). Edit it, then fold your change into the commit
of the patch it belongs to (for example `git commit --fixup <commit>` and `git rebase -i --autosquash upstream`), or
add a commit for a new patch. Then write the series back:

```bash
./gradlew :rhino:rebuildRhinoPatches  # regenerates patches/ from the commits after "upstream"
```

The patch files leave out version and diffstat noise, so a change to one shows up as a small diff, and keep only
`rhino/src/main`: tests you write in the working copy stay there. Delete `rhino/work` when you are done;
`applyRhinoPatches` refuses to overwrite it.

## Moving to a newer Rhino

Set `rhino_commit` and `rhino_sha256` (the SHA-256 of `https://github.com/mozilla/rhino/archive/<commit>.zip`),
run `applyRhinoPatches`, resolve whatever `git am` stops on, and run `rebuildRhinoPatches`.
