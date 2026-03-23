package mona.infrastructure.llm

import mona.domain.port.LlmToolDefinition

object ToolDefinitions {
    // Reusable JSON schema fragments (declared before use)
    private val lineItemSchema =
        """
        {
          "type": "object",
          "properties": {
            "description": {"type": "string", "description": "Description de la prestation ou du produit"},
            "quantity": {"type": "number", "description": "Quantite (peut etre decimale, ex: 1.5)"},
            "unit_price_euros": {"type": "number", "description": "Prix unitaire HT en euros (ex: 800.50)"}
          },
          "required": ["description", "quantity", "unit_price_euros"]
        }
        """.trimIndent()

    private val lineItemsProperty =
        """
        "line_items": {
          "type": "array",
          "description": "Lignes de la facture",
          "items": $lineItemSchema
        }
        """.trimIndent()

    private val activityTypeProperty =
        """
        "activity_type": {
          "type": "string",
          "enum": ["BIC_VENTE", "BIC_SERVICE", "BNC"],
          "description": "BIC_VENTE (vente), BIC_SERVICE (services commerciaux), BNC (professions liberales)"
        }
        """.trimIndent()

    private val invoiceRefProperties =
        """
        "invoice_number": {"type": "string", "description": "Numero de facture (ex: F-2024-01-001)"},
        "client_name": {"type": "string", "description": "Nom du client pour identifier la facture"}
        """.trimIndent()

    private val draftEditProperties =
        """
        $invoiceRefProperties,
        "new_client_name": {"type": "string", "description": "Nouveau nom du client"},
        $lineItemsProperty,
        "issue_date": {"type": "string", "description": "Date d'emission au format YYYY-MM-DD"},
        "payment_delay_days": {"type": "integer", "description": "Delai de paiement en jours (1-60)"},
        $activityTypeProperty
        """.trimIndent()

    // Tool definitions (declared before 'all')
    private val createInvoiceTool =
        LlmToolDefinition(
            name = "create_invoice",
            description = "Creer une nouvelle facture. Utiliser quand l'utilisateur demande de creer une facture.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "client_name": {"type": "string", "description": "Nom du client a facturer"},
                    $lineItemsProperty,
                    "issue_date": {"type": "string", "description": "Date d'emission YYYY-MM-DD. Defaut: aujourd'hui."},
                    "payment_delay_days": {"type": "integer", "description": "Delai de paiement en jours (1-60)."},
                    $activityTypeProperty
                  },
                  "required": ["client_name", "line_items"]
                }
                """.trimIndent(),
        )

    private val sendInvoiceTool =
        LlmToolDefinition(
            name = "send_invoice",
            description = "Envoyer une facture par email. Utiliser pour 'envoie la facture', 'envoie-la', etc.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    $invoiceRefProperties
                  }
                }
                """.trimIndent(),
        )

    private val markPaidTool =
        LlmToolDefinition(
            name = "mark_paid",
            description = "Marquer une facture comme payee. Utiliser pour 'j'ai ete paye', 'virement recu', etc.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    $invoiceRefProperties,
                    "payment_method": {
                      "type": "string",
                      "enum": ["VIREMENT", "CHEQUE", "ESPECES", "CARTE", "AUTRE"],
                      "description": "Moyen de paiement utilise"
                    },
                    "payment_date": {"type": "string", "description": "Date de paiement YYYY-MM-DD. Defaut: aujourd'hui."}
                  },
                  "required": ["payment_method"]
                }
                """.trimIndent(),
        )

    private val updateDraftTool =
        LlmToolDefinition(
            name = "update_draft",
            description = "Modifier un brouillon de facture. Utiliser pour corriger montant, client, lignes, etc.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    $draftEditProperties
                  }
                }
                """.trimIndent(),
        )

    private val deleteDraftTool =
        LlmToolDefinition(
            name = "delete_draft",
            description = "Supprimer un brouillon de facture. Le numero est libere. Uniquement pour les brouillons.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    $invoiceRefProperties
                  }
                }
                """.trimIndent(),
        )

    private val cancelInvoiceTool =
        LlmToolDefinition(
            name = "cancel_invoice",
            description = "Annuler une facture envoyee/payee avec avoir. Numero conserve. Pour factures sans correction.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    $invoiceRefProperties,
                    "reason": {"type": "string", "description": "Motif de l'annulation (optionnel)"}
                  }
                }
                """.trimIndent(),
        )

    private val correctInvoiceTool =
        LlmToolDefinition(
            name = "correct_invoice",
            description = "Corriger une facture envoyee/payee: avoir + facture corrigee. Pour corriger montant ou lignes.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    $draftEditProperties
                  }
                }
                """.trimIndent(),
        )

    private val getRevenueTool =
        LlmToolDefinition(
            name = "get_revenue",
            description = "Consulter le chiffre d'affaires. Utiliser quand l'utilisateur demande son CA ou ses revenus.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "period_type": {
                      "type": "string",
                      "enum": ["month", "quarter", "year"],
                      "description": "Type de periode: month (mois), quarter (trimestre), year (annee)"
                    },
                    "year": {"type": "integer", "description": "Annee (ex: 2024). Defaut: annee courante."},
                    "month": {"type": "integer", "description": "Mois 1-12, pour period_type=month."},
                    "quarter": {"type": "integer", "description": "Trimestre 1-4, pour period_type=quarter."}
                  },
                  "required": ["period_type"]
                }
                """.trimIndent(),
        )

    private val exportInvoicesTool =
        LlmToolDefinition(
            name = "export_invoices",
            description = "Exporter toutes les factures en CSV. Utiliser quand l'utilisateur demande un export CSV.",
            inputSchemaJson = """{"type": "object", "properties": {}}""",
        )

    private val getUnpaidTool =
        LlmToolDefinition(
            name = "get_unpaid",
            description = "Lister les factures impayees. Utiliser quand l'utilisateur demande ses impayes ou ce qu'on lui doit.",
            inputSchemaJson = """{"type": "object", "properties": {}}""",
        )

    private val updateClientTool =
        LlmToolDefinition(
            name = "update_client",
            description = "Mettre a jour les infos d'un client (email, adresse, SIRET, nom). Pour completer la fiche client.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "client_name": {"type": "string", "description": "Nom actuel du client a modifier"},
                    "new_name": {"type": "string", "description": "Nouveau nom du client"},
                    "email": {"type": "string", "description": "Adresse email du client"},
                    "address_street": {"type": "string", "description": "Rue et numero"},
                    "address_postal_code": {"type": "string", "description": "Code postal"},
                    "address_city": {"type": "string", "description": "Ville"},
                    "company_name": {"type": "string", "description": "Raison sociale de l'entreprise"},
                    "siret": {"type": "string", "description": "Numero SIRET (14 chiffres)"}
                  },
                  "required": ["client_name"]
                }
                """.trimIndent(),
        )

    private val updateProfileTool =
        LlmToolDefinition(
            name = "update_profile",
            description = "Mettre a jour le profil: SIREN, nom, IBAN, email, adresse, type d'activite, periodicite URSSAF.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "siren": {"type": "string", "description": "Numero SIREN (9 chiffres)"},
                    "name": {"type": "string", "description": "Nom complet de l'auto-entrepreneur"},
                    $activityTypeProperty,
                    "email": {"type": "string", "description": "Adresse email de l'auto-entrepreneur"},
                    "iban": {"type": "string", "description": "IBAN pour les virements"},
                    "payment_delay_days": {"type": "integer", "description": "Delai de paiement par defaut (1-60 jours)"},
                    "declaration_periodicity": {
                      "type": "string",
                      "enum": ["MONTHLY", "QUARTERLY"],
                      "description": "Periodicite de declaration URSSAF"
                    },
                    "address_street": {"type": "string", "description": "Rue et numero"},
                    "address_postal_code": {"type": "string", "description": "Code postal"},
                    "address_city": {"type": "string", "description": "Ville"}
                  }
                }
                """.trimIndent(),
        )

    private val configureSettingTool =
        LlmToolDefinition(
            name = "configure_setting",
            description = "Modifier un parametre du compte. Pour 'desactive la confirmation', 'delai de paiement 15j', etc.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "setting": {
                      "type": "string",
                      "enum": ["confirm_before_create", "default_payment_delay_days"],
                      "description": "Nom du parametre a modifier"
                    },
                    "value": {
                      "type": "string",
                      "description": "Nouvelle valeur ('false' pour confirm_before_create, '15' pour payment_delay_days)"
                    }
                  },
                  "required": ["setting", "value"]
                }
                """.trimIndent(),
        )

    private val listClientsTool =
        LlmToolDefinition(
            name = "list_clients",
            description = "Lister tous les clients avec leur resume (factures, CA). Utiliser pour la liste des clients.",
            inputSchemaJson = """{"type": "object", "properties": {}}""",
        )

    private val clientHistoryTool =
        LlmToolDefinition(
            name = "client_history",
            description = "Historique des factures pour un client specifique. Utiliser quand l'utilisateur demande ce qu'il facture.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "client_name": {"type": "string", "description": "Nom du client"}
                  },
                  "required": ["client_name"]
                }
                """.trimIndent(),
        )

    private val conversationalTool =
        LlmToolDefinition(
            name = "conversational",
            description = "Repondre a un message non-transactionnel: salutations, remerciements, stress. Bref et chaleureux.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "response": {"type": "string", "description": "Reponse conversationnelle en francais, breve"}
                  },
                  "required": ["response"]
                }
                """.trimIndent(),
        )

    private val unknownTool =
        LlmToolDefinition(
            name = "unknown",
            description = "Utiliser quand le message ne correspond a aucune action et qu'une clarification est necessaire.",
            inputSchemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "clarification": {
                      "type": "string",
                      "description": "Question de clarification a poser a l'utilisateur en francais"
                    }
                  },
                  "required": ["clarification"]
                }
                """.trimIndent(),
        )

    val all: List<LlmToolDefinition> =
        listOf(
            createInvoiceTool,
            sendInvoiceTool,
            markPaidTool,
            updateDraftTool,
            deleteDraftTool,
            cancelInvoiceTool,
            correctInvoiceTool,
            getRevenueTool,
            exportInvoicesTool,
            getUnpaidTool,
            updateClientTool,
            updateProfileTool,
            configureSettingTool,
            listClientsTool,
            clientHistoryTool,
            conversationalTool,
            unknownTool,
        )
}
