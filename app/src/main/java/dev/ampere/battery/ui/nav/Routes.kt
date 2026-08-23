package dev.ampere.battery.ui.nav

object Routes {
    const val NOW = "now"
    const val HEALTH = "health"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    val bottomBarItems = listOf(
        BottomItem(NOW, "Now"),
        BottomItem(HEALTH, "Health"),
        BottomItem(HISTORY, "History"),
        BottomItem(SETTINGS, "Settings"),
    )
}

data class BottomItem(val route: String, val label: String)
