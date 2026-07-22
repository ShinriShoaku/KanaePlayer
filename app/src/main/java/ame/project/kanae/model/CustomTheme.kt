/*
 * KanaePlayer -
 * Copyright (C) 2026 KanaePlayer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed WITHOUT ANY WARRANTY; see the
 * GNU General Public License for more details: <https://www.gnu.org/licenses/>.
 */

package ame.project.kanae.model

data class CustomTheme(
    val bgPrimary: Int? = null,
    val bgSecondary: Int? = null,
    val textPrimary: Int? = null,
    val textSecondary: Int? = null,
    val alpha: Int = 255
)
