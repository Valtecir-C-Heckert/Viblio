"""Local, interactive owner recovery; no HTTP endpoint."""
import getpass,os
import psycopg
from .accounts import password_hash

def main():
    email=input('E-mail do administrador: ').strip().lower()
    password=getpass.getpass('Nova senha (mínimo 12 caracteres): ')
    confirm=getpass.getpass('Repita a nova senha: ')
    if password!=confirm or not 12<=len(password)<=128:raise SystemExit('Senhas precisam coincidir e ter 12 a 128 caracteres.')
    with psycopg.connect(os.environ['DATABASE_URL']) as db:
        row=db.execute('UPDATE public.viblio_users SET password_hash=%s WHERE email=%s AND is_admin=true RETURNING id',(password_hash(password),email)).fetchone()
        if not row:raise SystemExit('Administrador não encontrado. Nenhuma conta foi criada.')
        db.execute('DELETE FROM public.viblio_sessions WHERE user_id=%s',(row[0],));db.execute('DELETE FROM public.viblio_recovery WHERE user_id=%s',(row[0],))
    print('Senha alterada. Acervo preservado. Entre novamente no aplicativo.')
if __name__=='__main__':main()
