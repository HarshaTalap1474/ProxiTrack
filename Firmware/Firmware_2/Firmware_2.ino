/* * Project: Smart RFID & BLE Tracking Node (DIPEX 2026)
 * Phase: DEVELOPMENT (Deep Sleep Disabled)
 * Hardware: ESP32-C3 Super Mini
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>

#define BUZZER_PIN 10
#define BUTTON_PIN GPIO_NUM_3
#define BATTERY_PIN 1

#define SERVICE_UUID        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define WRITE_CHAR_UUID     "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer* pServer = NULL;
bool deviceConnected = false;
bool shouldBuzz = false;
bool lastButtonState = HIGH;

// Server Callbacks: Tracks App connection
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("App Connected! Connection is stable.");
    }
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("App Disconnected. Restarting Advertising...");
      pServer->startAdvertising(); 
    }
};

// Characteristic Callbacks: Listens for the "1" from the App
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String value = pCharacteristic->getValue(); 
      if (value.length() > 0 && value[0] == '1') {
        shouldBuzz = true; 
      }
    }
};

void setup() {
  Serial.begin(115200);
  
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  // --- BLE SETUP ---
  BLEDevice::init("ProxiNode"); 
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  
  BLEService *pService = pServer->createService(SERVICE_UUID);
  BLECharacteristic *pChar = pService->createCharacteristic(
                                         WRITE_CHAR_UUID,
                                         BLECharacteristic::PROPERTY_WRITE
                                       );
  pChar->setCallbacks(new MyCallbacks());
  pService->start();

  // --- BATTERY & ADVERTISING ---
  int batteryLevel = map(analogRead(BATTERY_PIN), 0, 4095, 0, 100);
  
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  
  BLEAdvertisementData pAdvertisementData;
  String mData = "";
  mData += (char)0xFF; mData += (char)0xFF; // Generic Company ID
  mData += (char)batteryLevel; // Embed Battery Level
  
  pAdvertisementData.setManufacturerData(mData.c_str()); 
  pAdvertising->setAdvertisementData(pAdvertisementData);
  pAdvertising->setScanResponse(true);
  
  pAdvertising->start();
  
  Serial.println("\n=== SYSTEM AWAKE & READY ===");
  Serial.print("CRITICAL: Your MAC Address for the QR Code is: ");
  Serial.println(BLEDevice::getAddress().toString().c_str());
}

void loop() {
  // 1. Handle "Find Tag" Request (From App)
  if (shouldBuzz) {
      Serial.println("App triggered Buzzer!");
      tone(BUZZER_PIN, 2700, 1000); 
      delay(1100);
      shouldBuzz = false;
  }

  // 2. Handle "Find Phone" Request (Physical Button Press)
  bool currentButtonState = digitalRead(BUTTON_PIN);
  if (currentButtonState == LOW && lastButtonState == HIGH) { // Button is pressed (Active Low)
      Serial.println("Button Pressed! Paging Phone...");
      tone(BUZZER_PIN, 3000, 200); // Quick chirp for physical feedback
      
      // In a real app, you would notify a characteristic here to trigger the phone alarm.
      // We will build that into the Java App next.
      delay(200); // Simple debounce
  }
  lastButtonState = currentButtonState;

  delay(10); // Stability delay
}