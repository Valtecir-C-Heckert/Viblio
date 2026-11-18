package br.com.centralcasa.ui

import android.graphics.Bitmap

data class RestBook(val title: String, val author: String, val cover: Bitmap?, val progress: String, val percent: Int, val status: String)
