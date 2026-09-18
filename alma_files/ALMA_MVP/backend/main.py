from backend.app import AlmaApplication

def main():
    alma = AlmaApplication()
    print("ALMA MVP iniciado. Escribí 'salir' para terminar.")

    while True:
        message = input("Vos: ").strip()
        if message.lower() in {"salir", "exit"}:
            break

        result = alma.chat(
            user_id="local_user",
            session_id="local_session",
            message=message,
        )
        print(f"ALMA: {result['text']}")

if __name__ == "__main__":
    main()
