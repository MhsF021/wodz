import { Mail, Phone } from 'lucide-react';
import { LinkedinIcon } from './BrandIcons';

export function Footer() {
  return (
    <footer className="footer footer--compact">
      <div className="footer__inner">
        <span className="footer__copyright">© {new Date().getFullYear()} WODZ | Todos os direitos reservados</span>
        <nav className="footer__contacts" aria-label="Contato">
          <a href="mailto:mhsferreira021@gmail.com" aria-label="E-mail: mhsferreira021@gmail.com">
            <Mail size={15} strokeWidth={1.6} aria-hidden />
            <span>mhsferreira021@gmail.com</span>
          </a>
          <a href="tel:+5581981835196" aria-label="Telefone: +55 81 98183-5196">
            <Phone size={15} strokeWidth={1.6} aria-hidden />
            <span>+55 81 98183-5196</span>
          </a>
          <a href="https://www.linkedin.com/in/mhsf021/" aria-label="LinkedIn de Matheus Ferreira">
            <LinkedinIcon size={15} strokeWidth={1.6} aria-hidden />
            <span>LinkedIn</span>
          </a>
        </nav>
      </div>
    </footer>
  );
}
