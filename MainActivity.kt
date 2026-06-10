package com.example.mesh_using_nordic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.ByteBuffer
import java.util.UUID

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

import android.os.ParcelUuid
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.content.Context
import android.content.ContentValues
import android.util.Log 
import androidx.annotation.NonNull

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel

// Importa le classi principali della libreria Java di Nordic
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.MeshNetwork
//import no.nordicsemi.android.mesh.MeshProvisioningStatusCallbacks
//import no.nordicsemi.android.mesh.MeshMessageCallbacks 

import no.nordicsemi.android.mesh.provisionerstates.ProvisioningCapabilities
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.provisionerstates.ProvisioningState

import no.nordicsemi.android.mesh.transport.ProvisionedMeshNode
import no.nordicsemi.android.mesh.transport.ControlMessage
import no.nordicsemi.android.mesh.transport.MeshMessage

import no.nordicsemi.android.mesh.utils.MeshParserUtils 

class MainActivity: FlutterActivity(), MeshManagerCallbacks, 
    no.nordicsemi.android.mesh.MeshProvisioningStatusCallbacks, 
    no.nordicsemi.android.mesh.MeshStatusCallbacks  {

    // Bluetooth scanner stuff ################################################
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // salvo i nodi non provisionati della scansione
    private val unprovisionedNodesMap = HashMap<String, UnprovisionedMeshNode>()

    // UUID standard per i dispositivi Mesh Non Provisionati
    private val MESH_PROVISIONING_UUID: UUID = UUID.fromString("00001827-0000-1000-8000-00805f9b34fb")
    //#######################################################################################################


    // Il nome del canale. Deve essere identico a quello che userai in Flutter (Dart)
    private val CHANNEL = "com.example.mesh/native"
    private val EVENT_CHANNEL = "com.example.mesh/events"

    private var currentCapabilities: ProvisioningCapabilities? = null
    private var currentDevice: UnprovisionedMeshNode? = null
    
    // Dichiarazione della libreria Java
    private lateinit var meshManagerApi: MeshManagerApi
    private var eventSink: EventChannel.EventSink? = null // <--- Canale per trasmettere a Flutter

    private lateinit var meshGattManager: MeshGattManager // per connettere il nodo da provisionare

    fun invioStatoAFlutter(stato: String) {
        runOnUiThread {
            // Crea una mappa compatibile con il codice condizionale del tuo initState in Dart
            val eventoMap = HashMap<String, String>()
            eventoMap["type"] = "mesh_status"
            eventoMap["status"] = stato
            
            eventSink?.success(eventoMap)
        }
    }
    
    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        

        // Inizializza la libreria Java passando il contesto dell'applicazione Android
        meshManagerApi = MeshManagerApi(applicationContext)

        // Imposta la MainActivity come ricevitore dei callback
        meshManagerApi.setMeshManagerCallbacks(this)
        meshManagerApi.setProvisioningStatusCallbacks(this) // <--- Fondamentale per evitare il crash!
        meshManagerApi.setMeshStatusCallbacks(this)         // <--- Consigliato per i messaggi successivi

        // Inizializza il gestore GATT automatico passandogli l'API Mesh
        meshGattManager = MeshGattManager(applicationContext, meshManagerApi) { stato ->
            runOnUiThread {
                val eventoMap = HashMap<String, String>()
                eventoMap["type"] = "mesh_status"
                eventoMap["status"] = stato
                eventSink?.success(eventoMap)
            }
        }

        // Configura il MethodChannel per ascoltare i comandi inviati da Flutter
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                "inizializzaMesh" -> {
                    // Ottieni l'oggetto della rete (può essere nullo all'avvio)
                    val network = meshManagerApi.meshNetwork
                    
                    if (network != null) {
                        // In questa libreria la proprietà corretta è meshName (getMeshName() in Java)
                        val name = network.meshName
                        result.success("Libreria nRF nativa pronta! Rete attuale: $name")
                    } else {
                        // All'avvio o alla prima installazione l'oggetto è normalmente nullo 
                        // finché non carichi un database JSON o non crei una nuova rete
                        result.success("Libreria nRF nativa pronta! In attesa di configurazione rete.")
                    }
                }
                   
                "createNewNetwork" -> {
                    try {
                        val customNetKey = "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4" // Deve essere una stringa HEX di 32 caratteri
       
                        val timestamp = System.currentTimeMillis()
                        val instant = Instant.ofEpochMilli(timestamp)
                        val zonedDateTime = instant.atZone(ZoneId.systemDefault())
                        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                        val dataFormattata: String = zonedDateTime.format(formatter)

                        // Stringa JSON standard minima compatibile con la libreria Nordic
                        val emptyMeshJson = """
                        {
                        "${"$"}schema": "http://json-schema.org",
                        "id": "http://bluetooth.org",
                        "version": "1.0.0",
                        "meshUUID": "00000000000000000000000000000000",
                        "meshName": "Nuova_Rete_Mesh",
                        "timestamp": "${dataFormattata}",
                        "provisioners": [{
                            "provisionerName": "Flutter Provisioner",
                            "UUID": "11111111111111111111111111111111",
                            "allocatedUnicastRange": [{ "lowAddress": "0001", "highAddress": "00FF" }],
                            "allocatedGroupRange": [{ "lowAddress": "C000", "highAddress": "C0FF" }],
                            "allocatedSceneRange": [{ "firstScene": "0001", "lastScene": "00FF" }]
                        }],
                        "netKeys": [
                            {
                            "name": "Network Key 1",
                            "index": 0,
                            "key": "$customNetKey",
                            "minSecurity": "low",
                            "phase": 0,
                            "timestamp": "${dataFormattata}"
                            }
                        ],
                        "appKeys": [],
                        "nodes": []
                        }
                        """.trimIndent()

                        // Forza l'importazione del JSON vuoto per resettare lo stato interno
                        meshManagerApi.importMeshNetworkJson(emptyMeshJson)
                        
                        val newNetwork = meshManagerApi.meshNetwork

                        if(newNetwork?.meshName == null){
                            result.error("ERRORE. emptyMeshJson","${emptyMeshJson}", null)    
                        }else{
                            result.success("Nuova rete inizializzata con successo: ${newNetwork?.meshName}")
                        }
                    } catch (e: Exception) {
                        result.error("CREATE_ERROR", "Impossibile creare una nuova rete: ${e.message}", null)
                    }
                }
                
                "getNetworkDetails" -> {
                    val network = meshManagerApi.meshNetwork
                    if (network != null) {
                        val mappaDati = meshNetworkToMap(network)
                        result.success(mappaDati) // Kotlin invia l'HashMap che Flutter leggerà come Map
                    } else {
                        result.error("NO_NETWORK", "Nessuna rete attualmente caricata", null)
                    }
                }

                "exportMeshNetwork" -> { // chiedo alla libreria la network sotto forma di JSON
                    try {
                      
                        val jsonRete: String? = meshManagerApi.exportMeshNetwork()
                        
                        if (jsonRete != null) {
                            // Restituisce la stringa JSON direttamente a Flutter
                            result.success(jsonRete)
                        } else {
                            result.error("EXPORT_FAILED", "Impossibile esportare la rete: l'oggetto è vuoto o nullo", null)
                        }
                    } catch (e: Exception) {
                        result.error("EXPORT_ERROR", "Errore durante l'esportazione nativa: ${e.message}", null)
                    }
                }
             
                "importJsonFromDownloadsByName" -> {

                    val jsonString = call.argument<String>("jsonString") ?: ""

                    if (!jsonString.isNullOrEmpty()) {
                        // Passiamo la stringa alla libreria Nordic
                        meshManagerApi.importMeshNetworkJson(jsonString)

                        val importedNetwork = meshManagerApi.meshNetwork
                        result.success("Rete importata con successo: ${importedNetwork?.meshName}")
                        
                    } else {
                        result.error("FILE_EMPTY", "Il file trovato è vuoto", null)
                    }

                }

               "startProvisioning" -> {
                    val uuidStr = call.argument<String>("uuid")
                    val macAddress = call.argument<String>("macAddress")
                    
                    // CORREZIONE 1: Cerchiamo nella mappa usando il macAddress (o l'uuidStr, in base a come lo salvi nel ScanCallback)
                    // Se nel tuo ScanCallback fai unprovisionedNodesMap[result.device.address] = ..., allora qui serve macAddress.
                    val nodeToProvision = unprovisionedNodesMap[macAddress] ?: unprovisionedNodesMap[uuidStr]

                    if (nodeToProvision != null) {
                        try {
                            // Pulizia e formattazione dell'UUID (aggiunge i trattini se Flutter lo invia come stringa compatta)
                            val formattedUuid = if (uuidStr != null && !uuidStr.contains("-") && uuidStr.length == 32) {
                                uuidStr.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(), "$1-$2-$3-$4-$5")
                            } else {
                                uuidStr
                            }
                            
                            val uuidObj = UUID.fromString(formattedUuid)
                                                      

                            // Invia l'invito Mesh GATT (Attention timer standard di 5 secondi)
                            // Ora che viene chiamato dopo "DISPOSITIVO_PRONTO_MESH", il BleMeshManager scriverà subito sul GATT
                            meshManagerApi.identifyNode(uuidObj, 5)
                            
                            result.success("Invito di identificazione inviato al nodo con successo.")
                        } catch (e: Exception) {
                            result.error("PROVISIONING_ERROR", "Errore identificazione: ${e.message}", null)
                        }
                    } else {
                        result.error("NODE_NOT_FOUND", "Nodo non trovato nella mappa nativa. Verificare chiave di scansione.", null)
                    }
                }

                "connectToMeshNode" -> { // <--- NUOVO METODO PER FLUTTER
                    val macAddress = call.argument<String>("macAddress")
                    if (macAddress != null) {
                        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                        val device: BluetoothDevice? = bluetoothAdapter.getRemoteDevice(macAddress)
                        
                        if (device != null) {
                            // BleMeshManager gestisce la coda, i tentativi e la connessione in background
                            meshGattManager.connect(device)
                                .retry(3, 200) // Riprova 3 volte in caso di errore 133 di Android
                                .useAutoConnect(false)
                                .enqueue()
                            result.success("Tentativo di connessione GATT avviato per $macAddress")
                        } else {
                            result.error("DEVICE_NOT_FOUND", "Dispositivo non trovato", null)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "Indirizzo MAC mancante", null)
                    }
                }

                "startScan" -> {
                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val bluetoothAdapter = bluetoothManager.adapter
                    
                    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                        result.error("BLE_DISABLED", "Il Bluetooth è disattivato o non supportato", null)
                    } else {
                        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
                        startMeshScan()
                        result.success("Scansione avviata")
                    }
                }

                "stopScan" -> {
                    stopMeshScan()
                    result.success("Scansione interrotta")
                } else -> {
                    result.notImplemented()
                }
            }
        }

        // Configurazione dell'EventChannel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events // Salviamo il canale di comunicazione

                    // Controllo lo stato REALE prima di sparare un evento a caso
                    if (::meshGattManager.isInitialized) {
                        if (meshGattManager.isConnected) {
                            runOnUiThread {eventSink?.success("DISPOSITIVO_PRONTO_MESH")}
                        } else {
                            runOnUiThread {eventSink?.success("DISCONNESSO")}
                        }
                    } else {
                        runOnUiThread {eventSink?.success("DISCONNESSO")}
                    }

                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            }
        )
    }

    // =========================================================================
    // OVERRIDE DEI METODI OBBLIGATORI DI MeshManagerCallbacks
    // =========================================================================
    
    override fun onNetworkLoaded(meshNetwork: MeshNetwork?) {
        // Chiamato quando la rete viene caricata correttamente dal database
        if (meshNetwork != null) {
            val infoRete: HashMap<String, Any> = meshNetworkToMap(meshNetwork)
            val eventData = HashMap<String, Any>()
            eventData["type"] = "network_loaded" 
            eventData["event"] = "onNetworkLoaded"
            eventData["network"] = infoRete
            
            runOnUiThread { eventSink?.success(eventData) }
        }
    }

    override fun onNetworkUpdated(meshNetwork: MeshNetwork?) {
        // Chiamato quando avvengono modifiche sulla rete
         if (meshNetwork != null) {
            val infoRete: HashMap<String, Any> = meshNetworkToMap(meshNetwork)
            val eventData = HashMap<String, Any>()
            eventData["type"] = "network_updated" 
            eventData["event"] = "onNetworkUpdated"
            eventData["network"] = infoRete
            
            runOnUiThread { eventSink?.success(eventData) }
        }
    }

    override fun onNetworkLoadFailed(error: String?) {
        // Chiamato in caso di errore nel caricamento dal database
        val eventData = HashMap<String, Any>()
        eventData["type"] = "network_load_failed" 
        eventData["event"] = "onNetworkLoadFailed"
        eventData["error"] = error ?: "Unknown load error"
        
        runOnUiThread { eventSink?.success(eventData) }
    }

    override fun onNetworkImported(meshNetwork: MeshNetwork?) {
        // Chiamato quando l'importazione del JSON va a buon fine
        if (meshNetwork != null) {
            val infoRete: HashMap<String, Any> = meshNetworkToMap(meshNetwork)
            val eventData = HashMap<String, Any>()
            eventData["type"] = "network_imported" 
            eventData["event"] = "onNetworkImported"
            eventData["network"] = infoRete
            
            runOnUiThread { eventSink?.success(eventData) }
        }
    }
    
    override fun onNetworkImportFailed(error: String?) {
        // Chiamato se l'importazione del JSON fallisce
        val eventData = HashMap<String, Any>()
        eventData["type"] = "network_import_failed" 
        eventData["event"] = "onNetworkImportFailed"
        eventData["error"] = error ?: "Unknown import error"
        
        runOnUiThread { eventSink?.success(eventData) }
    }
        
    override fun onMeshPduCreated(pdu: ByteArray) {
        // Chiamato quando viene generato un pacchetto Mesh pronto da trasmettere
    }

     override fun getMtu(): Int {

        var mtu = 70 

        if (::meshGattManager.isInitialized) {
            mtu = 70
        } else {
            mtu = 23
        }

        println("_DBG_KT getMtu() mtu: ${mtu}")
        return mtu
    }

    override fun sendProvisioningPdu(meshNode: UnprovisionedMeshNode, pdu: ByteArray) {
        // Chiamato quando la libreria deve inviare pacchetti durante il provisioning BLE
        val eventData = HashMap<String, Any>()
        eventData["type"] = "provisioning_pdu"
        eventData["state"] = "pdu size: ${pdu.size}"
        println("_DBG_KT sendProvisioningPdu() pdu.size: ${pdu.size}")

        runOnUiThread { 
            eventSink?.success(eventData)
        }

        // Aggiungiamo un controllo di sicurezza per essere sicuri che la variabile sia pronta
        if (::meshGattManager.isInitialized) {
            // Eseguiamo la scrittura sul thread corretto per evitare blocchi asincroni
            runOnUiThread {
                println("_DBG_KT sendProvisioningPdu() Forzo l'invio della PDU al MeshGattManager...")
                meshGattManager.sendMeshPdu(pdu)
            }
        } else {
            println("_DBG_KT sendProvisioningPdu() ERRORE CRITICO: meshGattManager non è ancora inizializzato!")
        }
    }


    // =========================================================================
    // OVERRIDE DEI METODI OBBLIGATORI DI MeshStatusCallbacks
    // =========================================================================
    
    override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
        println("_DBG_KT onTransactionFailed()")

    }
    
    override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray?) {
        println("_DBG_KT onUnknownPduReceived()")
    }
    
    override fun onBlockAcknowledgementProcessed(dst: Int, message: ControlMessage) {
        println("_DBG_KT onBlockAcknowledgementProcessed()")
    }
    
    override fun onBlockAcknowledgementReceived(src: Int, message: ControlMessage) {
        println("_DBG_KT onBlockAcknowledgementReceived()")
    }
    
    override fun onHeartbeatMessageReceived(src: Int, message: ControlMessage) {
         println("_DBG_KT onHeartbeatMessageReceived()")
    }

    override fun onMeshMessageProcessed(dst: Int, meshMessage: no.nordicsemi.android.mesh.transport.MeshMessage) {
        println("_DBG_KT onMeshMessageProcessed()")
    }

    override fun onMeshMessageReceived(src: Int, meshMessage: no.nordicsemi.android.mesh.transport.MeshMessage) {
        println("_DBG_KT onMeshMessageReceived()")
    }

    override fun onMessageDecryptionFailed(meshLayer: String, errorMessage: String) {
        // Puoi lasciarlo vuoto o inserire un log di debug se la decrittazione fallisce
        println("_DBG_KT onMessageDecryptionFailed()")
    }



    // =========================================================================
    // OVERRIDE DEI METODI OBBLIGATORI DI MeshProvisioningStatusCallbacks
    // =========================================================================
  
    override fun onProvisioningStateChanged(meshNode: UnprovisionedMeshNode, state: ProvisioningState.States, responseData: ByteArray?) {
        
        println("_DBG_KT onProvisioningStateChanged() State: ${state.toString()}")

        val eventData = HashMap<String, Any>()
        eventData["type"] = "provisioning_status"
        eventData["state"] = state.toString()
        runOnUiThread { eventSink?.success(eventData) }
        
        // Il nodo ha risposto all'invito si può procedere col provisioning
        if (state == ProvisioningState.States.PROVISIONING_CAPABILITIES  || state.name == "PROVISIONING_CAPABILITIES") {

            println("_DBG_KT onProvisioningStateChanged() Le Capabilities sono arrivate! Configuro l'indirizzo...")
           
            currentDevice = meshNode
            currentCapabilities = meshNode.provisioningCapabilities

            val network = meshManagerApi.meshNetwork
            if (network != null) {
                val provisioner = network.selectedProvisioner
                if (provisioner != null) {
                    // Leggiamo quante funzionalità (elementi) ha il tuo prototipo
                    val numberOfElements = currentCapabilities?.numberOfElements?.toInt() ?: 1
                    
                    // Calcoliamo il prossimo indirizzo Unicast valido nella rete Mesh
                    val nextAddress = network.nextAvailableUnicastAddress(numberOfElements, provisioner)
                    
                    // Assegniamo l'indirizzo al nodo
                    meshNode.setUnicastAddress(nextAddress)
                    println("_DBG_KT Indirizzo Unicast assegnato con successo: $nextAddress")
                }
            }

            
            // // Inizializza lo stato di provisioning nativo sull'API
            // val provisioningHandler = meshManagerApi.getMeshProvisioningHandler()
            // // Associa i callback di stato al gestore interno di Nordic
            // // Nota: Se la tua classe MainActivity implementa MeshProvisioningStatusCallbacks, usa 'this'
            // meshManagerApi.setMeshProvisioningStatusCallbacks(this)




            // Diamo il via libera finale per inviare il pacchetto PROVISIONING_START
            try {
                meshManagerApi.startProvisioning(meshNode)
                println("_DBG_KT Inviato comando startProvisioning finale all'hardware.")
            } catch (e: Exception) {
                println("_DBG_KT Errore durante l'avvio del provisioning: ${e.message}")
            }
           
        } else if(state == ProvisioningState.States.PROVISIONING_INVITE  || state.name == "PROVISIONING_INVITE"){ 
            println("_DBG_KT onProvisioningStateChanged() - Arrivato: PROVISIONING_INVITE")
        }
         else {
            println("_DBG_KT onProvisioningStateChanged() - UNHANDLED state: ${state.toString()}")
        }
    }

    override fun onProvisioningFailed(meshNode: UnprovisionedMeshNode, state: ProvisioningState.States, responseData: ByteArray) {
        
        println("_DBG_KT onProvisioningFailed() - IL PROVISIONING È FALLITO! Stato: ${state.name}, Dati: ${responseData.size} byte")
        
        val eventData = HashMap<String, Any>()
        eventData["type"] = "provisioning_failed"
        eventData["state"] = state.toString()
        
        // CORRETTO: Usiamo responseData.size invece di pdu.size
        
        runOnUiThread { eventSink?.success(eventData) }
    }

    override fun onProvisioningCompleted(meshNode: ProvisionedMeshNode, state: ProvisioningState.States, data: ByteArray) {
        
        println("_DBG_KT onProvisioningCompleted() State: ${state.toString()}")
        
        val eventData = HashMap<String, Any>()
        eventData["type"] = "provisioning_success"
        eventData["nodeUuid"] = meshNode.uuid //no.nordicsemi.android.mesh.utils.MeshParserUtils.bytesToHex(uuidBytes, false)
        
        runOnUiThread { eventSink?.success(eventData) }
    }
   
 
    // SCAN  ======================================================================================================

    private fun startMeshScan() {
        if (isScanning) return
        isScanning = true
        
        // Filtro per cercare SOLO nodi che espongono il servizio di provisioning Mesh
        val filter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(MESH_PROVISIONING_UUID))
        .build()
        
        val filters = ArrayList<ScanFilter>()
        filters.add(filter)

        val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
        
        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }
    
    private fun stopMeshScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothLeScanner?.stopScan(scanCallback)
    }
    
    private val scanCallback = object : ScanCallback() {
        
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            val device = result.device ?: return
            val scanRecord = result.scanRecord ?: return
            
            // La libreria Nordic permette di estrarre i dati di provisioning (es: UUID del dispositivo)
            val serviceData = scanRecord.getServiceData(ParcelUuid(MESH_PROVISIONING_UUID))
            var deviceUuidStr = ""
            var deviceUuidBytes: ByteArray? = null
        
            var nordicUuidObject: UUID? = null

            // Se presenti, convertiamo i dati sui UUID in formato esadecimale leggibile
            if (serviceData != null && serviceData.size >= 16) {
                deviceUuidBytes = serviceData.copyOfRange(0, 16)
                                
                val byteBuffer = ByteBuffer.wrap(deviceUuidBytes)
                nordicUuidObject = UUID(byteBuffer.long, byteBuffer.long)
                
                deviceUuidStr = no.nordicsemi.android.mesh.utils.MeshParserUtils.bytesToHex(deviceUuidBytes, false)
            }
        
            if (device != null && deviceUuidBytes != null && deviceUuidStr.isNotEmpty()) {
                 try {
                    // 3. COSTRUTTORE DIRETTO: Creiamo l'oggetto UnprovisionedMeshNode compatibile con la mappa
                    // Passiamo l'oggetto UUID
                    val unprovisionedNode = UnprovisionedMeshNode(nordicUuidObject)
                    
                    // Inseriamo anche i dati relativi alla potenza del segnale (RSSI) se la libreria lo richiede
                    //unprovisionedNode.rssi = result.rssi

                    // Ora i tipi coincidono perfettamente!
                    unprovisionedNodesMap[deviceUuidStr] = unprovisionedNode
                } catch (e: Exception) {
                    // Gestione di versioni della libreria con parametri del costruttore differenti
                    // Se la tua versione richiede solo il BluetoothDevice, usa: UnprovisionedMeshNode(device)
                }
            }

            val deviceName = scanRecord?.deviceName ?: device.name ?: "Dispositivo Sconosciuto"
            val macAddress = device.address
            
            // Creiamo una mappa da inviare a Flutter tramite lo Stream esistente
            val nodeInfo = HashMap<String, Any>()
            nodeInfo["type"] = "unprovisioned_node_found"
            nodeInfo["name"] = deviceName
            nodeInfo["macAddress"] = macAddress
            nodeInfo["rssi"] = result.rssi
            if (deviceUuidStr.isNotEmpty()) {
                nodeInfo["deviceUuid"] = deviceUuidStr
            }
                     
            // Inviamo i dati a Flutter sul thread principale
            runOnUiThread {
                eventSink?.success(nodeInfo)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            runOnUiThread {
                eventSink?.error("SCAN_FAILED", "Errore scansione BLE: $errorCode", null)
            }
        }
    }
    
    // ======================================================================================================


    private fun meshNetworkToMap(network: MeshNetwork): HashMap<String, Any> {
        //if (network == null) return null
        
        val networkMap = HashMap<String, Any>()
        networkMap["meshName"] = network.meshName ?: ""
        networkMap["meshUuid"] = network.meshUUID ?: ""
        networkMap["totalNodes"] =  network.nodes?.size ?: 0
        
       // Recupera la prima Network Key disponibile nella rete
        val netKeysList = network.netKeys
        if (!netKeysList.isNullOrEmpty()) {
            val primaNetKey = netKeysList[0]
            
            // Estrae l'array di byte grezzi (ByteArray) della chiave
            val keyBytes = primaNetKey.key 
    
            if (keyBytes != null) {
                // CORREZIONE: Trasforma l'array di byte in una stringa esadecimale (es: "A1B2C3...")
                // Il secondo parametro false evita di aggiungere gli spazi tra i byte
                networkMap["networkKey"] = MeshParserUtils.bytesToHex(keyBytes, false)
            } else {
                networkMap["networkKey"] = ""
            }
        } else {
            networkMap["networkKey"] = ""
        }
    
        // Estrai la lista dei Provisioner registrati
        val provisionersList = ArrayList<HashMap<String, Any>>()
        network.provisioners?.forEach { p ->
            val pMap = HashMap<String, Any>()
            pMap["name"] = p.provisionerName ?: ""
            pMap["uuid"] = p.provisionerUuid ?: ""
            provisionersList.add(pMap)
        }
        networkMap["provisioners"] = provisionersList
    
        return networkMap
    }
}
