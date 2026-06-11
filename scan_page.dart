import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class ScanPage extends StatefulWidget {
  const ScanPage({super.key});

  @override
  State<ScanPage> createState() => _ScanPageState();
}

class _ScanPageState extends State<ScanPage> {

  static const MethodChannel methodChannel = MethodChannel('com.example.mesh/native');
   
  static const EventChannel eventChannel = EventChannel('com.example.mesh/events');

  // Sottoscrizione per poter chiudere il canale quando la pagina viene distrutta
  StreamSubscription? _meshSubscription;

  String _statusMessage = "?";
  String _scanStatus ="Stopped.";

  String? _currentProvisioningUuid;
  String? _currentProvisioningMac;

  final List<Map<String, dynamic>> _dispositiviTrovati = [];

  Future<void> avviaScansione() async {
    setState(() {
      _dispositiviTrovati.clear();
      _scanStatus="Scanning...";
    });

    await methodChannel.invokeMethod('startScan');
  }

  Future<void> fermaScansione() async {
    await methodChannel.invokeMethod('stopScan');
    setState(() {
      _scanStatus="Stopped.";
    });
  }
 
  Future<void> eseguiFlussoProvisioning(String uuid, String mac) async {
  
    await fermaScansione();

    setState(() {
      _statusMessage = "Connessione radio in corso a:\n$mac";
    });

    // Avviamo SOLO la connessione GATT.
    // Salviamo temporaneamente UUID e MAC nelle variabili di stato della pagina
    // per usarle non appena il canale nativo ci darà il via libera.
    _currentProvisioningUuid = uuid;
    _currentProvisioningMac = mac;

    await methodChannel.invokeMethod<String>(
      'connectToMeshNode',
      {'macAddress': mac},
    );
  }


  @override
  void initState() {
    super.initState();

    _meshSubscription = eventChannel.receiveBroadcastStream().listen(
      (dynamic evento) {
        if (evento is Map) {

          if (evento['type'] == 'unprovisioned_node_found') {
            setState(() {
              // Evita duplicati basandoti sul MAC Address
              final giaPresente = _dispositiviTrovati.any(
                (d) => d['macAddress'] == evento['macAddress'],
              );
              if (!giaPresente) {
                _dispositiviTrovati.add(Map<String, dynamic>.from(evento));
                _statusMessage = "Found: ${_dispositiviTrovati.length}";
              }
            });
          } 
          // Gestione degli eventi di stato stringa inviati nativamente
          else if (evento['type'] == 'mesh_status'){
            final String statusStr = evento['status'] ?? '';
            _aggiornaStatoDallaStringaNativa(statusStr);
          } 
          else {
            debugPrint("Event: type: ${evento['type']} - ${evento.toString()}");
            setState(() {
              _statusMessage = "Event: ${evento.toString()}";
            });
          }
        }
        else if (evento is String) {
          _aggiornaStatoDallaStringaNativa(evento);
        } else {
          debugPrint("network_page EVENTO con data non Map e non String");
        }
      },
      onError: (dynamic errore) {
        debugPrint("Errore: ${errore.message}");
        setState(() {
          _statusMessage = "Errore: ${errore.message}";
        });
      },
    );
  }

  /// Centralizza la risposta agli stati generati dal BleMeshManager nativo
  void _aggiornaStatoDallaStringaNativa(String stato) {
    debugPrint("STATO BleMeshManager ricevuto: $stato");
    setState(() {
      switch (stato) {
        case "CONNESSIONE_IN_CORSO":
          _statusMessage = "Stabilendo connessione BLE radio...";
          break;
        case "CONNESSO_GATT":
          _statusMessage = "Radio connessa. Analisi dei servizi Mesh...";
          break;
        case "SERVIZI_SCOPERTI":
          _statusMessage = "Servizi GATT trovati. Configurazione notifiche...";
          break;
        case "DISPOSITIVO_PRONTO_MESH":
           _statusMessage = "Connessione GATT Pronta!\nAvvio del provisioning in corso...";
          // Il canale è pronto! Ora possiamo svegliare la libreria Mesh in sicurezza
          if (_currentProvisioningUuid != null && _currentProvisioningMac != null) {
            methodChannel.invokeMethod('startProvisioning', {
              'uuid': _currentProvisioningUuid,
              'macAddress': _currentProvisioningMac,
            }).then((risultato) {
              debugPrint("Mesh startProvisioning invocato: $risultato");
            }).catchError((e) {
              setState(() { _statusMessage = "Errore avvio: ${e.message}"; });
            });
          }
          break;
        case "DISCONNESSO":
          _statusMessage = "Dispositivo disconnesso.";
          break;
        default:
          _statusMessage = "Stato: $stato";
      }
    });
  }

  @override
  void dispose() {
    // Chiudiamo lo stream per evitare perdite di memoria (memory leak)
    _meshSubscription?.cancel();
    super.dispose();
  }


  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SCAN'),
        centerTitle: true,
        backgroundColor: Colors.blue, // Puoi cambiare il colore qui
        titleTextStyle:  const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: Colors.white
              ),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              ElevatedButton(
                onPressed: () {
                  Navigator.pop(context);
                },
                child: const Text('<- Network page'),
              ),
              const SizedBox(height: 10),

              ColoredBox(
                color: const Color.fromARGB(255, 187, 220, 224), // Imposta qui il colore di sfondo
                child:  Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    ElevatedButton(
                      onPressed: avviaScansione,
                      child: const Text('Start Scan!'),
                    ),
                    
                    Text((_scanStatus)),

                    ElevatedButton(
                      onPressed: fermaScansione,
                      child: const Text('Stop Scan.'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 15),

              // Box dedicato a mostrare lo stato di avanzamento della connessione/provisioning
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey[200],
                  borderRadius: BorderRadius.circular(8),
                ),
                width: double.infinity,
                child: Text(
                  _statusMessage,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: Colors.blueAccent,
                  ),
                ),
              ),
              const SizedBox(height: 15),
          

              // Lista dei Dispositivi Trovati
              Expanded(
                child: _dispositiviTrovati.isEmpty
                    ? const Center(
                        child: Text(
                          "Nessun dispositivo non provisionato trovato",
                        ),
                      )
                    : ListView.builder(
                        itemCount: _dispositiviTrovati.length,
                        itemBuilder: (context, index) {
                          final dispositivo = _dispositiviTrovati[index];
                          final name = dispositivo['name'] ?? 'Dispositivo Ignoto';
                          final mac = dispositivo['macAddress'] ?? 'N/A';
                          final uuid = dispositivo['deviceUuid'] ?? '';
                          final rssi = dispositivo['rssi'] ?? 0;

                          return Card(
                            margin: const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 6,
                            ),
                            child: ListTile(
                              leading: const Icon(
                                Icons.bluetooth,
                                color: Colors.blue,
                              ),
                              title: Text(
                                name,
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              subtitle: Text(
                                "MAC: $mac\nRSSI: $rssi dBm\nUUID: ${uuid.substring(0, 8)}...",
                              ),
                              isThreeLine: true,
                              trailing: ElevatedButton(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: Colors.green,
                                  foregroundColor: Colors.white,
                                ),
                                onPressed:  uuid.isEmpty ? null : () => eseguiFlussoProvisioning(uuid, mac),
                                child: const Text('Provision'),
                              ),
                            ),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
