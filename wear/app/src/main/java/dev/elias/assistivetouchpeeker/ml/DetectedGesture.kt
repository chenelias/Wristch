package dev.elias.assistivetouchpeeker.ml

/** A fired detection: one of the model's built-in classes, or a user-enrolled custom gesture. */
sealed interface DetectedGesture {
    data class Base(val gestureClass: GestureClass) : DetectedGesture
    data class Custom(val id: String, val name: String) : DetectedGesture
}
