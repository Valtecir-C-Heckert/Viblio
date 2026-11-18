package br.com.centralcasa.books

import java.text.Normalizer
import java.util.Locale

/** Conservative corrections for legacy catalogue titles, never guesses an article for an unknown work. */
object LegacyBookMetadata {
    data class Metadata(val title: String, val sequenceNumber: Int?)
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    private val corrections = mutableMapOf<String, String>().apply {
        fun titles(author: String, vararg pairs: Pair<String, String>) {
            pairs.forEach { (old, corrected) -> put(key(author) + ":" + key(old), corrected) }
        }
        titles("Pierce Brown", "Furia Vermelha" to "Fúria Vermelha")
        titles("Suzanne Collins", "Esperança" to "A Esperança")
        titles("Robert Kirkman", "Caminho para Woodbury" to "O Caminho para Woodbury", "Queda do Governador - Parte 1" to "A Queda do Governador - Parte 1", "Ascensão do Governador" to "A Ascensão do Governador")
        titles("Brandon Sanderson", "Poço da Ascensão" to "O Poço da Ascensão", "Heroi das Eras" to "O Herói das Eras", "Caminho dos Reis" to "O Caminho dos Reis")
        titles("George R. R. Martin", "Guerra dos Tronos" to "A Guerra dos Tronos")
        titles("R.F. Kuang", "Guerra da papoula" to "A Guerra da Papoula", "República do dragão" to "A República do Dragão", "Deusa em chamas" to "A Deusa em Chamas")
        titles("Andrzej Sapkowski", "Último Desejo" to "O Último Desejo", "Espada do Destino" to "A Espada do Destino", "Torre da Andorinha" to "A Torre da Andorinha", "Senhora do Lago" to "A Senhora do Lago")
        titles("Patrick Rothfuss", "Nome do Vento" to "O Nome do Vento")
        titles("C. S. Lewis", "Leão, a Feiticeira e o Guarda-Roupa" to "O Leão, a Feiticeira e o Guarda-Roupa", "Sobrinho do Mago" to "O Sobrinho do Mago", "Viagem do Peregrino da Alvorada" to "A Viagem do Peregrino da Alvorada", "Cadeira de Prata" to "A Cadeira de Prata", "Cavalo e seu Menino" to "O Cavalo e seu Menino", "Última Batalha" to "A Última Batalha")
        titles("J. K. Rowling", "Pedra Filosofal" to "Harry Potter e a Pedra Filosofal", "Câmara Secreta" to "Harry Potter e a Câmara Secreta", "Prisioneiro de Azkaban" to "Harry Potter e o Prisioneiro de Azkaban", "Cálice de Fogo" to "Harry Potter e o Cálice de Fogo", "Ordem da Fênix" to "Harry Potter e a Ordem da Fênix", "Enigma do Príncipe" to "Harry Potter e o Enigma do Príncipe", "Relíquias da Morte" to "Harry Potter e as Relíquias da Morte", "Criança Amaldiçoada" to "Harry Potter e a Criança Amaldiçoada")
        titles("J. R. R. Tolkien", "Hobbit" to "O Hobbit", "Sociedade do Anel" to "A Sociedade do Anel", "Duas Torres," to "As Duas Torres", "Retorno do Rei" to "O Retorno do Rei", "Silmarillion" to "O Silmarillion")
        titles("Isaac Asimov", "Fundação," to "Fundação", "Robôs da Alvorada" to "Os Robôs da Alvorada", "Sol Desvelado" to "O Sol Desvelado")
        titles("Philip K. Dick", "Homem do Castelo Alto" to "O Homem do Castelo Alto")
        titles("Fiódor Dostoiévski", "Jogador" to "O Jogador")
        titles("Franz Kafka", "Metamorfose" to "A Metamorfose")
        titles("Antoine de Saint-Exupéry", "Pequeno Príncipe" to "O Pequeno Príncipe")
        titles("Bernard Cornwell", "Arqueiro" to "O Arqueiro", "Andarilho" to "O Andarilho", "Herege" to "O Herege", "Rei do Inverno" to "O Rei do Inverno", "Inimigo de Deus" to "O Inimigo de Deus", "Último Reino" to "O Último Reino", "Cavaleiro da Morte" to "O Cavaleiro da Morte", "Senhores do Norte" to "Os Senhores do Norte", "Canção da Espada" to "A Canção da Espada")
        titles("Sun Tzu", "Arte da Guerra" to "A Arte da Guerra")
        titles("Roberto Gómez Bolaños", "Diário de Chaves" to "O Diário do Chaves")
        titles("Sophie Deram", "Peso das dietas" to "O Peso das Dietas")
        titles("Bram Stoker", "Dracula" to "Drácula", "Hospede de Drácula" to "O Hóspede de Drácula")
        titles("Gaston Leroux", "Fantasma da Ópera" to "O Fantasma da Ópera")
        titles("Robert Louis Stevenson", "Médico e o Monstro" to "O Médico e o Monstro")
        titles("Oscar Wilde", "Retrato de Dorian Gray" to "O Retrato de Dorian Gray")
        titles("Jhon Fowles", "Colecionador" to "O Colecionador")
        titles("Thomas Harris", "Silêncio dos Inocentes" to "O Silêncio dos Inocentes")
        titles("Alex Michaelides", "Paciente Silenciosa" to "A Paciente Silenciosa")
        titles("Ira Levin", "Bebê de Rosemary" to "O Bebê de Rosemary")
        titles("Willian Peter Blatty", "Exorcista" to "O Exorcista")
        titles("John Langan", "Pescador" to "O Pescador")
        titles("Stephen King", "Iluminado" to "O Iluminado")
    }
    fun read(title: String, author: String, explicitNumber: Int? = null): Metadata {
        val trimmed = title.trim()
        val ordinal = Regex("^(\\d{1,4})[º°ª]\\s+(.+)$").matchEntire(trimmed)
        // A bare leading number is only extracted for a recognized work: 1984 and 7 Pilares remain titles.
        val bare = Regex("^(\\d{1,4})\\s+(.+)$").matchEntire(trimmed)?.takeIf {
            corrections.containsKey(key(author) + ":" + key(it.groupValues[2]))
        }
        val match = ordinal ?: bare
        val clean = match?.groupValues?.get(2) ?: trimmed
        return Metadata(corrections[key(author) + ":" + key(clean)] ?: clean, explicitNumber ?: match?.groupValues?.get(1)?.toInt())
    }
}
