# hubitat-apps

Custom Hubitat apps and drivers by Thorvald Natvig.

## Afar Room

Afar Room is one package containing two required Hubitat apps:

- [Afar Room](apps/afar-room/afar-room.groovy): the parent app, which coordinates room activity, master buttons, and lighting timers.
- [Afar Room Child](apps/afar-room/afar-room-child.groovy): each room or zone's lights, buttons, and motion sensors.

Both apps use the `thorvald` namespace. The initial package version is `1.0.0`.

## Hubitat Package Manager

### Add this repository

In HPM, open **Package Manager Settings → Add a Custom Repository** and enter this repository URL:

```text
https://raw.githubusercontent.com/thorvald/hubitat-apps/main/repository.json
```

### Link an existing installation

After adding the repository, run **Match Up**. Turn **Fast Match off** so HPM searches the custom repository locally. Confirm the matches for Afar Room and Afar Room Child.

If the hub is already running this `1.0.0` source, tell HPM the installed version is up to date. HPM can then track future package updates.

### Install a new copy

In HPM, choose **Install → From a URL** and enter the package manifest URL:

```text
https://raw.githubusercontent.com/thorvald/hubitat-apps/main/apps/afar-room/packageManifest.json
```

The package installs the parent and child app code. In Hubitat, use **Add User App** to add **Afar Room**, finish installing the parent, then open it to add room children.

### Updating the package

Keep the app IDs in `packageManifest.json` unchanged across releases. Update the package version, release date, and release notes when publishing new code; keep the versions reported by both apps aligned with the package version.

HPM reads the files on the `main` branch. Publish code and manifest changes together.

## References

- [HPM documentation](https://hubitatpackagemanager.hubitatcommunity.com/)
- [HPM developer documentation](https://hubitatpackagemanager.hubitatcommunity.com/devs1.html)
