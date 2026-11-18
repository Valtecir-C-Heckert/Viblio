package br.com.centralcasa.services

data class Rgb(val red: Int, val green: Int, val blue: Int) {
    init { require(listOf(red, green, blue).all { it in 0..255 }) }
}

