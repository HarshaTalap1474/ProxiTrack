/* * Project: ProxiTrack - Official Hardware Handover (DIPEX 2026)
 * Firmware: V7.0 (Production Build: Security + Deep Sleep)
 * Hardware: ESP32-C3 Super Mini
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <Preferences.h>
#include <esp_sleep.h> // REQUIRED FOR DEEP SLEEP

// --- PIN DEFINITIONS ---
#define BUZZER_PIN 10
#define BUTTON_PIN 3

// --- CONTRACT UUIDS ---
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUTH_CHAR_UUID      "8d8218b6-97bc-4527-a8db-130940ddb633"
#define CMD_CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// --- GLOBAL STATE ---
Preferences prefs;
BLEServer* pServer = NULL;
String stored_pin = "0000";
volatile bool isUnlocked = false; 

// POWER MANAGEMENT VARIABLES
bool deviceConnected = false;
unsigned long bootTime = 0;
const unsigned long AWAKE_WINDOW_MS = 10000; // Stay awake for 10 seconds
const uint64_t SLEEP_DURATION_US = 5000000;  // Deep Sleep for 5 seconds

// --- GATT CALLBACKS ---

class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true; // CANCEL SLEEP TIMER
        isUnlocked = false; 
        Serial.println("STATUS: App Connected. Sleep Timer Canceled!");
        
        pServer->getAdvertising()->start();
    }
    
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false; // RESTART SLEEP LOGIC
        isUnlocked = false;
        digitalWrite(BUZZER_PIN, LOW); 
        Serial.println("STATUS: App Disconnected. Preparing for sleep cycle...");
        
        bootTime = millis(); // Reset timer to give a fresh 10s window to reconnect
        pServer->getAdvertising()->start();
    }
};

class AuthCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            String incomingPin = rxValue; 
            if (stored_pin == "0000") {
                stored_pin = incomingPin;
                prefs.putString("pin", stored_pin);
                isUnlocked = true;
                Serial.println("SECURITY: Tag Claimed! Locked to PIN: " + stored_pin);
            } else if (incomingPin == stored_pin) {
                isUnlocked = true;
                Serial.println("SECURITY: Auth Success! Tag Unlocked.");
            } else {
                isUnlocked = false;
                Serial.println("SECURITY: Auth Failed! Incorrect PIN.");
            }
        }
    }
};

class CmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        if (!isUnlocked) {
            Serial.println("REJECTED: Device is LOCKED.");
            return;
        }
        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            if (rxValue[0] == '1') {
                digitalWrite(BUZZER_PIN, HIGH);
                Serial.println("COMMAND: Buzzer ON");
            } else if (rxValue[0] == '0') {
                digitalWrite(BUZZER_PIN, LOW);
                Serial.println("COMMAND: Buzzer OFF");
            }
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(2000); // Allow USB CDC to mount
    
    bootTime = millis(); // START THE CLOCK
    
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(BUZZER_PIN, LOW);

    prefs.begin("proxitrack", false);
    stored_pin = prefs.getString("pin", "0000"); 
    Serial.println("\n--- ProxiNode Waking Up ---");

    // Initialize BLE
    BLEDevice::init("ProxiNode");
    
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);

    BLECharacteristic *pAuthChar = pService->createCharacteristic(
                                        AUTH_CHAR_UUID,
                                        BLECharacteristic::PROPERTY_WRITE
                                    );
    pAuthChar->setCallbacks(new AuthCallbacks());

    BLECharacteristic *pCmdChar = pService->createCharacteristic(
                                        CMD_CHAR_UUID,
                                        BLECharacteristic::PROPERTY_WRITE
                                    );
    pCmdChar->setCallbacks(new CmdCallbacks());

    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); 
    pAdvertising->start();

    // Configure Sleep Timer
    esp_sleep_enable_timer_wakeup(SLEEP_DURATION_US);

    Serial.println("SYSTEM READY: Advertising for 10 seconds...");
}

void loop() {
    // POWER MANAGEMENT LOGIC
    // If NO phone is connected, and our 10 seconds are up...
    if (!deviceConnected && (millis() - bootTime > AWAKE_WINDOW_MS)) {
        Serial.println("POWER: No connection detected. Entering Deep Sleep for 5 seconds...");
        
        // Gracefully shut down BLE to prevent memory leaks
        pServer->getAdvertising()->stop();
        BLEDevice::deinit(true);
        
        // Go to sleep. The ESP32 will reboot from the start of setup() when it wakes up.
        esp_deep_sleep_start();
    }
    
    // If deviceConnected is true, this loop just spins freely, keeping the tag awake!
    delay(10);
}