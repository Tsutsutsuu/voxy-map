
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2-fabric"

stonecutter parameters {
    val version = current.project.split('-', limit = 2)[0]
    val loader = current.project.split('-', limit = 2)[1]
    properties { tags(version, loader) }
    constants { match(loader, "fabric") }
    swaps["mod_version"] = "\"${properties.get<String>("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"

    replacements {
        // 26.1 still exposes Minecraft.screen directly; 26.2 moved it behind Gui
        string(current.parsed < "26.2") {
            replace("client.gui.screen()", "client.screen")
            replace("Minecraft.getInstance().gui.screen()", "Minecraft.getInstance().screen")
        }
    }
}
