package com.rysal.happytv.ui.focus

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

/**
 * True for the keys that mean "OK" on a remote or keyboard: the D-pad centre, Enter and
 * the numpad Enter. The one place this three-key test is written out.
 */
fun KeyEvent.isOk(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
