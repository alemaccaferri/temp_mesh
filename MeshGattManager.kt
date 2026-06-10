package com.example.mesh_using_nordic

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.mesh.MeshManagerApi
import java.util.UUID

class MeshGattManager(
    context: Context,
    private val meshManagerApi: MeshManagerApi,
    private val onStatusChanged: (String) -> Unit
) : BleManager(context) {

    companion object {
        val MESH_PROVISIONING_SERVICE_UUID: UUID = UUID.fromString("00001827-0000-1000-8000-00805f9b34fb")
        val MESH_PROVISIONING_DATA_IN: UUID = UUID.fromString("00002adb-0000-1000-8000-00805f9b34fb")
        val MESH_PROVISIONING_DATA_OUT: UUID = UUID.fromString("00002adc-0000-1000-8000-00805f9b34fb")

        val MESH_PROXY_SERVICE_UUID: UUID = UUID.fromString("00001828-0000-1000-8000-00805f9b34fb")
        val MESH_PROXY_DATA_IN: UUID = UUID.fromString("00002add-0000-1000-8000-00805f9b34fb")
        val MESH_PROXY_DATA_OUT: UUID = UUID.fromString("00002ade-0000-1000-8000-00805f9b34fb")
    }

    private var dataInCharacteristic: BluetoothGattCharacteristic? = null
    private var dataOutCharacteristic: BluetoothGattCharacteristic? = null
  

    fun sendMeshPdu(pdu: ByteArray) {
        if (dataInCharacteristic != null) {
            println("_DBG_KT sendMeshPdu() Sto scrivendo fisicamente sul BLE GATT ${pdu.size} byte.")
            writeCharacteristic(
                dataInCharacteristic,
                pdu,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ).enqueue()
        } else {
            println("_DBG_KT sendMeshPdu() ATTENZIONE: Impossibile inviare PDU. dataInCharacteristic è NULLA!")
        }
    }

    override fun getGattCallback(): BleManagerGattCallback = MeshGattCallback()

    private inner class MeshGattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            // 1. Cerchiamo il servizio di Provisioning (il nodo non è ancora configurato)
            val provisioningService = gatt.getService(MESH_PROVISIONING_SERVICE_UUID)
            if (provisioningService != null) {
                // Estraiamo le caratteristiche esatte
                dataInCharacteristic = provisioningService.getCharacteristic(MESH_PROVISIONING_DATA_IN)
                dataOutCharacteristic = provisioningService.getCharacteristic(MESH_PROVISIONING_DATA_OUT)
                
                // Stampiamo un log nativo per verificare se le ha trovate davvero
                println("_DBG_KT isRequiredServiceSupported() Caratteristiche Provisioning trovate: IN=${dataInCharacteristic != null}, OUT=${dataOutCharacteristic != null}")
                
                return dataInCharacteristic != null && dataOutCharacteristic != null
            }

            // 2. Se non lo trova, prova con il servizio Proxy (per nodi già configurati)
            val proxyService = gatt.getService(MESH_PROXY_SERVICE_UUID)
            if (proxyService != null) {
                dataInCharacteristic = proxyService.getCharacteristic(MESH_PROXY_DATA_IN)
                dataOutCharacteristic = proxyService.getCharacteristic(MESH_PROXY_DATA_OUT)
                println("_DBG_KT isRequiredServiceSupported() Caratteristiche Proxy trovate: IN=${dataInCharacteristic != null}, OUT=${dataOutCharacteristic != null}")
                return dataInCharacteristic != null && dataOutCharacteristic != null
            }

            println("_DBG_KT isRequiredServiceSupported() ERRORE: Servizi Mesh non trovati sul dispositivo GATT.")
            return false
        }
  

    override fun initialize() {

        // Android negozierà il valore massimo supportato dal tuo prototipo.
        //requestMtu(517).enqueue()

        setNotificationCallback(dataOutCharacteristic).with { _, data ->
            val bytes = data.value
            val mtu = 247
            if (bytes != null) {
                println("_DBG_KT initialize() setNotificationCallback(). data.size: ${bytes.size}, MTU attuale: $mtu")
                // Invia le notifiche GATT in entrata direttamente all'API Mesh
                try {
                    // Proviamo ad alimentare la rete passando la MTU dinamica reale negoziata dal modulo BLE
                    meshManagerApi.handleNotifications(mtu, bytes)
                } catch (e: Exception) {
                    println("_DBG_KT initialize() Errore durante l'iniezione delle notifiche: ${e.message}")
                }
            }
        }
        enableNotifications(dataOutCharacteristic).enqueue()
        onStatusChanged("SERVIZI_SCOPERTI")
    }

    override fun onDeviceReady() {
        // Quando il canale radio GATT è pronto e le notifiche sono attive,
        // informiamo semplicemente Flutter. La libreria Mesh capirà lo stato
        // non appena inizierai a inviare la PDU di provisioning.
        onStatusChanged("DISPOSITIVO_PRONTO_MESH")

        //meshManagerApi.handleConnected(device) 

    }

    override fun onServicesInvalidated() {
        // Quando il dispositivo si disconnette, notifichiamo Flutter.
        onStatusChanged("DISCONNESSO")
    }
}
}
