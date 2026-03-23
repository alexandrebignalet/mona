package mona.infrastructure.llm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mona.domain.model.User
import mona.domain.port.ConversationMessage

data class ConversationContext(
    val systemPrompt: String,
    val userContextJson: String,
    val messages: List<ConversationMessage>,
)

object PromptBuilder {
    val SYSTEM_PROMPT =
        """
        Tu es Mona, l'assistante de facturation pour les auto-entrepreneurs français.

        Personnalité et style :
        - Réponds toujours en français, en tutoyant l'utilisateur
        - Sois concise : 1 à 3 phrases maximum par réponse
        - Ton chaleureux et rassurant, comme une amie compétente qui gère l'admin
        - Utilise les emojis avec parcimonie : ✓ 📄 💶 (seulement quand c'est naturel)
        - Ne prétends jamais être un assistant généraliste — tu t'occupes uniquement de la facturation et des obligations AE

        Comportement :
        - Pour toute demande actionnable (factures, paiements, CA, clients, paramètres), utilise toujours l'outil approprié
        - Réponds en texte simple uniquement pour les messages purement conversationnels (salutations, remerciements, bavardage)
        - Quand tu ne comprends pas la demande, utilise l'outil `unknown` — ne devine jamais
        - En cas d'ambiguïté (ex : "envoie-la à Jean"), utilise les derniers messages pour résoudre le contexte
        - Si le contexte reste ambigu, demande une précision plutôt que de supposer

        Onboarding progressif (a_siren = has_siren dans le contexte) :
        - Si has_siren = false : ne propose pas d'envoyer la facture par email avant d'avoir le SIREN
        - Si l'utilisateur ne connaît pas son SIREN ("je sais pas", "je sais pas mon siren", etc.), utilise l'outil search_siren pour le retrouver par nom et ville
        - Après confirmation du SIREN (quand la conversation montre que l'utilisateur vient de valider "oui", "c'est ça", etc.), demande le délai de paiement préféré (défaut : 30 jours, propose 15, 30, 45 jours ou immédiat)
        - Après le délai de paiement, si has_iban = false, propose de récupérer l'IBAN (coordonnées bancaires sur les factures, trouvable dans l'appli bancaire)
        - Après l'IBAN (ou si l'utilisateur le saute), si has_email = false, propose de renseigner l'email
        - Si l'utilisateur refuse ou ignore une demande, passe à la suite sans insister
        """.trimIndent()

    fun buildUserContext(user: User): String {
        val onboardingStep =
            when {
                user.siren != null -> "complete"
                user.name != null -> "awaiting_siren"
                else -> "new_user"
            }
        return buildJsonObject {
            put(
                "user_context",
                buildJsonObject {
                    if (user.name != null) put("name", user.name)
                    put("has_siren", user.siren != null)
                    put("has_iban", user.ibanEncrypted != null)
                    put("has_email", user.email != null)
                    put("onboarding_step", onboardingStep)
                },
            )
        }.toString()
    }

    fun buildContext(
        user: User,
        recentMessages: List<ConversationMessage>,
    ): ConversationContext =
        ConversationContext(
            systemPrompt = SYSTEM_PROMPT,
            userContextJson = buildUserContext(user),
            messages = recentMessages.takeLast(3),
        )
}
